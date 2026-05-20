<?php

namespace App\Controllers\Api;

use App\Models\Api\SessionsModel;
use App\Support\ApiErrorMessages;
use App\Support\ApiEventTypes;
use CodeIgniter\HTTP\ResponseInterface;
use Random\RandomException;

final class Sessions extends ApiBaseController
{
    use SessionLifecycleTrait;

    private const PRE_STREAM_GRACE_SECONDS = 30;

    /**
     * @throws RandomException
     */
    public function handshake(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $body = $this->validateJson('apiHandshake');
        if ($body instanceof ResponseInterface) {
            return $body;
        }
        $accessKey = $body['accessKey'];
        $autoCreate = $body['autoCreate'];
        $chatEncrypted = $body['chatEncrypted'];

        $model = new SessionsModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null) {
            return $this->jsonError(ApiErrorMessages::SERVER_NOT_FOUND, 500);
        }
        $missedPingLimit = (int) $serverSettings['client_missed_ping_limit'];

        $session = $model->findSession($sessionName);
        if ($session !== null) {
            $accessError = $this->validateSessionAccess($session, $accessKey, $chatEncrypted);
            if ($accessError !== null) {
                return $accessError;
            }

            $this->cleanupUnresponsiveClients($model, (int) $session['id'], $missedPingLimit);
            $session = $model->findSession($sessionName);
            if ($session !== null) {
                $accessError = $this->validateSessionAccess($session, $accessKey, $chatEncrypted);
                if ($accessError !== null) {
                    return $accessError;
                }
            }
        }

        if ($session === null) {
            if (!$autoCreate) {
                return $this->jsonError(ApiErrorMessages::SESSION_NOT_FOUND, 404);
            }
            $nonce = $this->newNonce();
            $accessKeyHash = $this->hashAccessKeyForStore($accessKey);
            if ($accessKeyHash === null) {
                return $this->jsonError(ApiErrorMessages::UNABLE_TO_HASH_ACCESS_KEY, 500);
            }
            $created = $this->unwrapResult($model->findOrCreateSession(
                $sessionName,
                $accessKeyHash,
                $nonce,
                $chatEncrypted,
                (int) $serverSettings['session_limit'],
            ));
            if ($created instanceof ResponseInterface) {
                return $created;
            }
            $session = $created['session'];
            $accessError = $this->validateSessionAccess($session, $accessKey, $chatEncrypted);
            if ($accessError !== null) {
                return $accessError;
            }
        }

        $bodyOut = 'sessionNonce=' . rawurlencode((string) $session['session_nonce']) . "\n";
        return $this->response
            ->setHeader('Content-Type', 'text/plain; charset=utf-8')
            ->setBody($bodyOut);
    }

    /**
     * @throws RandomException
     */
    public function connect(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $body = $this->validateJson('apiConnect');
        if ($body instanceof ResponseInterface) {
            return $body;
        }
        $accessKey = $body['accessKey'];
        $name = $body['name'];
        $chatEncrypted = $body['chatEncrypted'];

        $model = new SessionsModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null) {
            return $this->jsonError(ApiErrorMessages::SERVER_NOT_FOUND, 500);
        }
        $missedPingLimit = (int) $serverSettings['client_missed_ping_limit'];

        $session = $model->findSession($sessionName);
        if ($session === null) {
            return $this->jsonError(ApiErrorMessages::SESSION_NOT_FOUND, 404);
        }
        $accessError = $this->validateSessionAccess($session, $accessKey, $chatEncrypted);
        if ($accessError !== null) {
            return $accessError;
        }

        $sessionId = (int) $session['id'];
        $this->cleanupUnresponsiveClients($model, $sessionId, $missedPingLimit);

        $token = bin2hex(random_bytes(32));
        $admission = $this->unwrapResult($model->admitClient(
            $sessionId,
            (int) $serverSettings['user_limit_per_session'],
            $token,
            $name,
            $this->request->getIPAddress(),
        ));
        if ($admission instanceof ResponseInterface) {
            return $admission;
        }
        $clientId = (int) $admission['clientId'];

        $recipientIds = $model->recipientIds($sessionId, $clientId);
        $model->enqueueMessage($recipientIds, [
            'sender_id' => $clientId,
            'type' => ApiEventTypes::USER_JOIN,
            'content' => $name,
            'file_id' => null,
        ]);

        $clients = $model->listClients($sessionId);
        $users = [];
        foreach ($clients as $c) {
            $id = (int) $c['id'];
            $users[(string) $id] = (string) $c['name'];
        }

        $lines = [
            'token=' . $token,
            'clientId=' . $clientId,
            'messageCharacterLimit=' . (int) $serverSettings['message_character_limit'],
            'fileSizeBytesLimit=' . (int) $serverSettings['file_size_bytes_limit'],
            'fileTransferEnabled=' . ((int) $serverSettings['file_count_limit_per_session'] >= 0 ? 'true' : 'false'),
        ];
        foreach ($users as $id => $n) {
            $lines[] = 'user.' . $id . '=' . rawurlencode($n);
        }
        return $this->response
            ->setHeader('Content-Type', 'text/plain; charset=utf-8')
            ->setBody(implode("\n", $lines) . "\n");
    }

    public function heartbeat(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $model = new SessionsModel();
        $context = $this->requireSessionClient($model, $sessionName);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $session = $context['session'];
        $client = $context['client'];
        $sessionId = (int) $session['id'];
        $clientId = (int) $client['id'];
        $model->touchClient($clientId);
        $model->acknowledgeMessages($clientId, $this->lastEventIdHeader());
        return $this->jsonOk();
    }

    public function disconnect(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $model = new SessionsModel();
        $context = $this->requireSessionClient($model, $sessionName);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $client = $context['client'];
        $sessionId = (int) $client['session_id'];
        $clientId = (int) $client['id'];
        $model->acknowledgeMessages($clientId, $this->lastEventIdHeader());
        $sessionClosed = $this->finalizeDisconnect($model, $sessionId, $clientId);
        return $this->response->setJSON([
            'ok' => true,
            'sessionClosed' => $sessionClosed,
        ]);
    }

    private function accessKeyMatches(string $stored, string $provided): bool
    {
        if ($stored === '') {
            return true;
        }
        if ($provided === '') {
            return false;
        }

        return password_verify($provided, $stored);
    }

    private function hashAccessKeyForStore(string $accessKey): ?string
    {
        if ($accessKey === '') {
            return '';
        }

        $hash = password_hash($accessKey, PASSWORD_DEFAULT);
        return $hash === false ? null : $hash;
    }

    private function validateSessionAccess(array $session, string $accessKey, bool $chatEncrypted): ?ResponseInterface
    {
        if (!$this->accessKeyMatches((string) $session['access_key'], $accessKey)) {
            return $this->jsonError(ApiErrorMessages::ACCESS_KEY_MISMATCH, 403);
        }
        if ((int) $session['chat_encrypted'] !== ($chatEncrypted ? 1 : 0)) {
            return $this->jsonError(ApiErrorMessages::CHAT_ENCRYPTION_MODE_MISMATCH, 409);
        }
        return null;
    }

    /**
     * @throws RandomException
     */
    private function newNonce(): string
    {
        return base64_encode(random_bytes(32));
    }

    private function cleanupUnresponsiveClients(SessionsModel $model, int $sessionId, int $missedPingLimit): void
    {
        $staleClientIds = $model->stalePreStreamClientIds($sessionId, self::PRE_STREAM_GRACE_SECONDS);
        $staleLookup = array_fill_keys($staleClientIds, true);
        $clientIds = array_values(array_unique(array_merge(
            $model->unresponsiveClientIds($sessionId, $missedPingLimit),
            $staleClientIds,
        )));
        foreach ($clientIds as $clientId) {
            if ($clientId > 0) {
                if (isset($staleLookup[$clientId])) {
                    $this->cleanupPendingClient($model, $sessionId, $clientId);
                    continue;
                }
                $this->finalizeDisconnect($model, $sessionId, $clientId);
            }
        }
    }

    private function cleanupPendingClient(SessionsModel $model, int $sessionId, int $clientId): void
    {
        if ($sessionId < 1 || $clientId < 1) {
            return;
        }

        $this->cleanupTusUploadsForClient($sessionId, $clientId);

        if (!$model->clientExists($clientId)) {
            return;
        }

        $model->deleteClient($clientId);

        $remaining = $model->countClientsInSession($sessionId);
        if ($remaining === 0) {
            $this->cleanupOrphanFiles($model, $sessionId);
            $this->deleteSessionFiles($sessionId);
            $model->deleteSession($sessionId);
            return;
        }

        $this->cleanupOrphanFiles($model, $sessionId);
        $recipientIds = $model->recipientIds($sessionId, $clientId);
        $model->enqueueMessage($recipientIds, [
            'sender_id' => $clientId,
            'type' => ApiEventTypes::USER_LEAVE,
            'content' => '',
            'file_id' => null,
        ]);
    }
}
