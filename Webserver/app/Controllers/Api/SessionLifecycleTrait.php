<?php

namespace App\Controllers\Api;

use App\Models\Api\SessionsModel;
use App\Support\ApiEventTypes;
use App\Support\Filesystem;
use App\Support\TusStorage;
use TusPhp\Cache\FileStore;
use TusPhp\Config as TusConfig;

trait SessionLifecycleTrait
{
    private function finalizeDisconnect(
        SessionsModel $model,
        int $sessionId,
        int $clientId,
    ): bool {
        if ($sessionId < 1 || $clientId < 1) {
            return false;
        }

        $this->cleanupTusUploadsForClient($sessionId, $clientId);

        $clientExists = $model->clientExists($clientId);
        if ($clientExists) {
            $model->deleteClient($clientId);
        }

        $remaining = $model->countClientsInSession($sessionId);
        if ($remaining === 0) {
            $this->deleteSessionFiles($sessionId);
            $model->deleteSession($sessionId);
            return true;
        }

        $this->cleanupOrphanFiles($model, $sessionId);

        if ($clientExists) {
            $recipientIds = $model->recipientIds($sessionId, $clientId);
            $model->enqueueMessage($recipientIds, [
                'sender_id' => $clientId,
                'type' => ApiEventTypes::USER_LEAVE,
                'content' => '',
                'file_id' => null,
            ]);
        }

        return false;
    }

    private function cleanupTusUploadsForClient(int $sessionId, int $clientId): void
    {
        if ($sessionId < 1 || $clientId < 1) {
            return;
        }

        $cacheDir = rtrim(TusStorage::cacheRoot(), '\\/') . DIRECTORY_SEPARATOR;
        if (!is_dir($cacheDir)) {
            return;
        }

        TusConfig::set([
            'file' => [
                'dir' => $cacheDir,
                'name' => 'tus_php.server.cache',
            ],
        ], true);

        $cache = new FileStore($cacheDir, 'tus_php.server.cache');
        $cache->setPrefix('tus:server:');

        $keys = $cache->keys();
        if (empty($keys)) {
            return;
        }

        $uploadRoot = TusStorage::uploadRoot();
        foreach ($keys as $key) {
            $meta = $cache->get($key, true);
            if (!is_array($meta)) {
                continue;
            }
            if ((int) $meta['client_id'] !== $clientId) {
                continue;
            }
            if ((int) $meta['session_id'] !== $sessionId) {
                continue;
            }

            $cache->delete($key);

            TusStorage::deleteUploadFile((string) ($meta['file_path'] ?? ''), $uploadRoot);
        }
    }

    private function cleanupOrphanFiles(SessionsModel $model, int $sessionId): void
    {
        $fileIds = $model->orphanFileIds($sessionId);
        foreach ($fileIds as $fileId) {
            if ($fileId < 1) {
                continue;
            }
            $model->deleteFile($fileId);
            Filesystem::deleteDir($this->uploadDir($sessionId, $fileId));
        }
    }

    private function deleteSessionFiles(int $sessionId): void
    {
        Filesystem::deleteDir(rtrim(WRITEPATH, '\\/') . '/uploads/' . $sessionId);
    }

    private function uploadDir(int $sessionId, int $fileId): string
    {
        return rtrim(WRITEPATH, '\\/') . '/uploads/' . $sessionId . '/' . $fileId;
    }

}
