<?php

namespace App\Controllers\Api;

use App\Models\Api\FilesModel;
use App\Support\ApiErrorMessages;
use App\Support\TusStorage;
use CodeIgniter\HTTP\ResponseInterface;
use TusPhp\Config as TusConfig;
use TusPhp\Events\TusEvent;
use TusPhp\Events\UploadCreated;
use TusPhp\Events\UploadComplete;
use TusPhp\Tus\Server as TusServer;
use Symfony\Component\HttpFoundation\Response as HttpResponse;

final class TusUploads extends ApiBaseController
{
    private const TUS_CACHE_TTL_SECONDS = 31536000;

    public function uploads(string $sessionName, ?string $uploadKey = null)
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $token = $this->requireToken();
        if ($token instanceof ResponseInterface) {
            return $token;
        }

        $method = strtoupper($this->request->getMethod());
        $model = new FilesModel();
        $fileSizeBytesLimit = 0;

        if ($method === 'POST') {
            $lengthHeader = trim($this->request->getHeaderLine('Upload-Length'));
            $length = (int) $lengthHeader;
            $check = $this->unwrapResult($model->uploadContext($sessionName, $token, $length));
            if ($check instanceof ResponseInterface) {
                return $check;
            }
            $fileSizeBytesLimit = (int) $check['fileSizeBytesLimit'];
        } else {
            $context = $this->requireSessionClient($model, $sessionName);
            if ($context instanceof ResponseInterface) {
                return $context;
            }
            $clientId = (int) $context['client']['id'];
            $model->touchClient($clientId);
            $check = [
                'clientId' => $clientId,
                'sessionId' => (int) $context['session']['id'],
            ];
        }
        $clientId = (int) $check['clientId'];
        $sessionId = (int) $check['sessionId'];

        $cacheRoot = TusStorage::cacheRoot();
        if (!is_dir($cacheRoot) && !mkdir($cacheRoot, 0777, true) && !is_dir($cacheRoot)) {
            return $this->jsonError(ApiErrorMessages::UNABLE_TO_CREATE_CACHE_DIRECTORY, 500);
        }

        $uploadRoot = TusStorage::uploadRoot();
        if (!is_dir($uploadRoot) && !mkdir($uploadRoot, 0777, true) && !is_dir($uploadRoot)) {
            return $this->jsonError(ApiErrorMessages::UNABLE_TO_CREATE_UPLOAD_DIRECTORY, 500);
        }

        $this->configureTus($cacheRoot);

        $server = new TusServer('file');
        $server->setApiPath('/api/sessions/' . rawurlencode($sessionName) . '/uploads');
        $server->getCache()->setTtl(self::TUS_CACHE_TTL_SECONDS);
        if ($fileSizeBytesLimit > 0) {
            $server->setMaxUploadSize($fileSizeBytesLimit);
        }

        $requestedKey = $method === 'POST' ? $server->getUploadKey() : $server->getRequest()->key();
        if ($method === 'OPTIONS' && $requestedKey === '') {
            $uploadDir = $uploadRoot;
        } else {
            $uploadDir = TusStorage::uploadDir($uploadRoot, $requestedKey);
            if ($uploadDir === null) {
                return $this->jsonError(ApiErrorMessages::INVALID_UPLOAD_KEY, 400);
            }
        }

        if ($method === 'POST') {
            if (!is_dir($uploadDir) && !mkdir($uploadDir, 0777, true) && !is_dir($uploadDir)) {
                return $this->jsonError(ApiErrorMessages::UNABLE_TO_CREATE_UPLOAD_DIRECTORY, 500);
            }
        }
        $server->setUploadDir($uploadDir);

        if ($method === 'POST' && $clientId > 0 && $sessionId > 0) {
            $server->event()->addListener(UploadCreated::NAME, function (TusEvent $event) use ($server, $clientId, $sessionId) {
                $key = $event->getFile()->getKey();
                if ($key === '') {
                    return;
                }
                $cache = $server->getCache();
                $meta = $cache->get($key, true);
                if (!is_array($meta)) {
                    $meta = [];
                }
                $meta['client_id'] = $clientId;
                $meta['session_id'] = $sessionId;
                $cache->set($key, $meta);
            });
        }

        $finalizeError = null;
        /**
         * @throws RandomException
         */
        $server->event()->addListener(UploadComplete::NAME, function (TusEvent $event) use ($model, $sessionName, $token, &$finalizeError) {
            $details = $event->getFile()->details();
            $metadata = $details['metadata'];
            $rawName = (string) $metadata['filename'];
            $fileName = $this->safeFileName($rawName);
            $tempPath = (string) $details['file_path'];
            $fileSize = (int) $details['size'];
            $result = $model->finalizeTusUpload($sessionName, $token, $fileName, $tempPath, $fileSize);
            if (array_key_exists('error', $result)) {
                $finalizeError = $result;
            }
        });

        $response = $server->serve();

        if ($method === 'PATCH' && method_exists($response, 'getStatusCode')) {
            if ($response->getStatusCode() === HttpResponse::HTTP_CONTINUE) {
                $this->cleanupAbortedTusUpload($server);
            }
        }

        if ($finalizeError !== null) {
            $status = (int) $finalizeError['status'];
            $message = (string) $finalizeError['error'];
            $response = $server->getResponse()->send(
                ['error' => $message],
                $status,
                ['Content-Type' => 'application/json']
            );
        }

        $response->send();
        exit;
    }

    private function configureTus(string $cacheRoot): void
    {
        TusConfig::set([
            'file' => [
                'dir' => rtrim($cacheRoot, '\\/') . DIRECTORY_SEPARATOR,
                'name' => 'tus_php.server.cache',
            ],
        ], true);
    }

    private function cleanupAbortedTusUpload(TusServer $server): void
    {
        $key = $server->getRequest()->key();
        if ($key === '') {
            return;
        }

        $cache = $server->getCache();
        $meta = $cache->get($key, true);
        $cache->delete($key);

        if (!is_array($meta)) {
            return;
        }

        TusStorage::deleteUploadFile((string) ($meta['file_path'] ?? ''), TusStorage::uploadRoot());
    }
}
