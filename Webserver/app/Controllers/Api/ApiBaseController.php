<?php

namespace App\Controllers\Api;

use App\Controllers\BaseController;
use App\Models\Api\ApiModel;
use App\Support\ApiErrorMessages;
use CodeIgniter\HTTP\ResponseInterface;

abstract class ApiBaseController extends BaseController
{
    private const MAX_SESSION_NAME_CHARACTERS = 64;
    private const TOKEN_HEADER = 'X-ThatsApp-Token';

    protected function normalizeSessionName(string $sessionName): string
    {
        return trim($sessionName);
    }

    protected function requireSessionName(string $sessionName): string|ResponseInterface
    {
        $normalized = $this->normalizeSessionName($sessionName);
        if ($normalized === '') {
            return $this->jsonError(ApiErrorMessages::SESSION_NAME_CANNOT_BE_EMPTY, 400);
        }
        if (str_contains($normalized, '/')) {
            return $this->jsonError(ApiErrorMessages::SESSION_NAME_MUST_NOT_CONTAIN_SLASH, 400);
        }
        $count = preg_match_all('/./us', $normalized);
        if ($count === false) {
            return $this->jsonError(ApiErrorMessages::INVALID_SESSION_NAME, 400);
        }
        if ($count > self::MAX_SESSION_NAME_CHARACTERS) {
            return $this->jsonError(ApiErrorMessages::SESSION_NAME_MAX_64, 400);
        }
        return $normalized;
    }

    protected function safeFileName(string $name): string
    {
        $name = trim($name);
        if ($name === '') {
            return '';
        }
        $name = str_replace(['\\', '/', "\0"], '_', $name);
        return basename($name);
    }

    protected function jsonError(string $message, int $status): ResponseInterface
    {
        return $this->response->setStatusCode($status)->setJSON(['error' => $message]);
    }

    protected function jsonOk(?array $payload = null): ResponseInterface
    {
        return $this->response->setJSON($payload ?? ['ok' => true]);
    }

    protected function unwrapResult(array $result): array|ResponseInterface
    {
        if (array_key_exists('error', $result)) {
            return $this->jsonError((string) $result['error'], (int) $result['status']);
        }
        return $result;
    }

    protected function validateJson(array|string $rules): array|ResponseInterface
    {
        $body = $this->request->getJSON(true);
        if (!is_array($body)) {
            return $this->jsonError(ApiErrorMessages::INVALID_JSON_BODY, 400);
        }
        if (! $this->validateData($body, $rules)) {
            return $this->validationErrorResponse(400);
        }
        return $body;
    }

    protected function requireToken(): string|ResponseInterface
    {
        $token = trim($this->request->getHeaderLine(self::TOKEN_HEADER));
        if ($token === '') {
            return $this->jsonError(ApiErrorMessages::INVALID_TOKEN, 403);
        }
        if (! $this->validateData(['token' => $token], 'apiToken')) {
            return $this->validationErrorResponse(403);
        }
        return (string) $token;
    }

    protected function requireSessionClient(
        ApiModel $model,
        string $sessionName,
        string $missingSessionMessage = ApiErrorMessages::INVALID_TOKEN,
        int $missingSessionStatus = 403,
        bool $sessionFirst = false,
    ): array|ResponseInterface {
        $session = null;
        if ($sessionFirst) {
            $session = $model->findSession($sessionName);
            if ($session === null) {
                return $this->jsonError($missingSessionMessage, $missingSessionStatus);
            }
        }

        $token = $this->requireToken();
        if ($token instanceof ResponseInterface) {
            return $token;
        }

        if ($session === null) {
            $session = $model->findSession($sessionName);
            if ($session === null) {
                return $this->jsonError($missingSessionMessage, $missingSessionStatus);
            }
        }

        $client = $model->findClientBySessionToken((int) $session['id'], $token);
        if ($client === null) {
            return $this->jsonError(ApiErrorMessages::INVALID_TOKEN, 403);
        }

        return [
            'session' => $session,
            'client' => $client,
        ];
    }

    protected function lastEventIdHeader(string $headerName = 'X-Last-Event-ID'): int
    {
        $value = trim($this->request->getHeaderLine($headerName));
        if ($value === '' || !ctype_digit($value)) {
            return 0;
        }
        return (int) $value;
    }

    private function validationErrorResponse(int $status): ResponseInterface
    {
        $errors = $this->validator ? $this->validator->getErrors() : [];
        $message = $errors === [] ? ApiErrorMessages::INVALID_REQUEST : (string) array_values($errors)[0];
        return $this->jsonError($message, $status);
    }

}
