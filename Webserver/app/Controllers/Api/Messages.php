<?php

namespace App\Controllers\Api;

use App\Models\Api\ApiModel;
use App\Support\ApiErrorMessages;
use App\Support\ApiEventTypes;
use CodeIgniter\HTTP\ResponseInterface;

final class Messages extends ApiBaseController
{
    private const CHAT_PLAIN_PREFIX = 'P2:';
    private const CHAT_ENCRYPTED_PREFIX = 'E2:';
    private const CHAT_ENCRYPTED_OVERHEAD_BYTES = 28;
    private const CHAT_CHARACTER_BYTES = 4;
    private const TYPING_PLAIN_MAX_DIGITS = 19;
    private const TYPING_ENCRYPTED_MAX_CHARACTERS = 128;
    private const TYPING_ENCRYPTED_MIN_BYTES = 29;
    private const TYPING_ENCRYPTED_MAX_BYTES = 64;

    public function messages(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $body = $this->validateJson('apiMessages');
        if ($body instanceof ResponseInterface) {
            return $body;
        }
        $message = $body['message'];

        $model = new ApiModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null) {
            return $this->jsonError(ApiErrorMessages::SERVER_NOT_FOUND, 500);
        }
        $context = $this->requireSessionClient($model, $sessionName);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $session = $context['session'];
        $client = $context['client'];
        $validation = $this->validateMessagePayload($message, (int) $serverSettings['message_character_limit'], (int) $session['chat_encrypted'] === 1);
        if ($validation instanceof ResponseInterface) {
            return $validation;
        }

        $clientId = (int) $client['id'];
        $model->touchClient($clientId);

        $recipientIds = $model->recipientIds((int) $session['id'], $clientId);
        $model->enqueueMessage($recipientIds, [
            'sender_id' => $clientId,
            'type' => ApiEventTypes::MESSAGE,
            'content' => $message,
            'file_id' => null,
        ]);

        return $this->jsonOk();
    }

    public function typing(string $sessionName): ResponseInterface
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $body = $this->validateJson('apiTyping');
        if ($body instanceof ResponseInterface) {
            return $body;
        }
        $payload = $body['payload'];

        $model = new ApiModel();
        $context = $this->requireSessionClient($model, $sessionName);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $session = $context['session'];
        $client = $context['client'];
        $validation = $this->validateTypingPayload($payload, (int) $session['chat_encrypted'] === 1);
        if ($validation instanceof ResponseInterface) {
            return $validation;
        }

        $clientId = (int) $client['id'];
        $model->touchClient($clientId);

        $recipientIds = $model->recipientIds((int) $session['id'], $clientId);
        $model->enqueueMessage($recipientIds, [
            'sender_id' => $clientId,
            'type' => ApiEventTypes::USER_TYPING,
            'content' => $payload,
            'file_id' => null,
        ]);

        return $this->jsonOk();
    }

    private function validateMessagePayload(string $payload, int $characterLimit, bool $chatEncrypted): ?ResponseInterface
    {
        if ($chatEncrypted !== str_starts_with($payload, self::CHAT_ENCRYPTED_PREFIX)) {
            return $this->jsonError(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, 400);
        }
        try {
            $characterCount = $this->countPayloadCharacters($payload);
        } catch (\InvalidArgumentException) {
            return $this->jsonError(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, 400);
        }
        if ($characterLimit > 0 && $characterCount > $characterLimit) {
            return $this->jsonError(ApiErrorMessages::messageExceedsLimit($characterLimit), 413);
        }
        return null;
    }

    private function validateTypingPayload(string $payload, bool $chatEncrypted): ?ResponseInterface
    {
        if (!$chatEncrypted) {
            if (preg_match('/^\d{1,' . self::TYPING_PLAIN_MAX_DIGITS . '}$/', $payload) === 1) {
                return null;
            }
            return $this->jsonError(ApiErrorMessages::INVALID_TYPING_PAYLOAD, 400);
        }

        if ($payload === '' || strlen($payload) > self::TYPING_ENCRYPTED_MAX_CHARACTERS) {
            return $this->jsonError(ApiErrorMessages::INVALID_TYPING_PAYLOAD, 400);
        }

        $decoded = base64_decode($payload, true);
        if ($decoded === false) {
            return $this->jsonError(ApiErrorMessages::INVALID_TYPING_PAYLOAD, 400);
        }

        $decodedLength = strlen($decoded);
        if ($decodedLength < self::TYPING_ENCRYPTED_MIN_BYTES || $decodedLength > self::TYPING_ENCRYPTED_MAX_BYTES) {
            return $this->jsonError(ApiErrorMessages::INVALID_TYPING_PAYLOAD, 400);
        }

        return null;
    }

    private function countPayloadCharacters(string $payload): int
    {
        if (str_starts_with($payload, self::CHAT_PLAIN_PREFIX)) {
            $count = preg_match_all('/./us', substr($payload, strlen(self::CHAT_PLAIN_PREFIX)));
            if ($count === false) {
                throw new \InvalidArgumentException('Invalid message payload');
            }
            return $count;
        }
        if (!str_starts_with($payload, self::CHAT_ENCRYPTED_PREFIX)) {
            throw new \InvalidArgumentException('Invalid message payload');
        }
        $decoded = base64_decode(substr($payload, strlen(self::CHAT_ENCRYPTED_PREFIX)), true);
        if ($decoded === false) {
            throw new \InvalidArgumentException('Invalid message payload');
        }
        $plaintextBytes = strlen($decoded) - self::CHAT_ENCRYPTED_OVERHEAD_BYTES;
        if ($plaintextBytes < 0 || $plaintextBytes % self::CHAT_CHARACTER_BYTES !== 0) {
            throw new \InvalidArgumentException('Invalid message payload');
        }
        return intdiv($plaintextBytes, self::CHAT_CHARACTER_BYTES);
    }
}
