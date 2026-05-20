<?php

use App\Controllers\Api\ApiBaseController;
use App\Support\ApiErrorMessages;
use CodeIgniter\HTTP\ResponseInterface;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class ApiBaseControllerTest extends CIUnitTestCase
{
    public function testRequireSessionNameRejectsInvalidValues(): void
    {
        $this->assertSame(
            ApiErrorMessages::SESSION_NAME_CANNOT_BE_EMPTY,
            $this->jsonBody($this->newController()->exposeRequireSessionName('   '))['error'],
        );
        $this->assertSame(
            ApiErrorMessages::SESSION_NAME_MUST_NOT_CONTAIN_SLASH,
            $this->jsonBody($this->newController()->exposeRequireSessionName('chat/room'))['error'],
        );
        $this->assertSame(
            ApiErrorMessages::SESSION_NAME_MAX_64,
            $this->jsonBody($this->newController()->exposeRequireSessionName(str_repeat('a', 65)))['error'],
        );
    }

    public function testRequireSessionNameReturnsNormalizedValue(): void
    {
        $controller = $this->newController();

        $this->assertSame('room', $controller->exposeRequireSessionName(' room '));
    }

    public function testSanitizesFileNamesAndReadsEventHeader(): void
    {
        $request = service('request');
        $request->setHeader('X-Last-Event-ID', '15');
        $controller = $this->newController($request);

        $this->assertSame('.._secret.txt', $controller->exposeSafeFileName('../secret.txt'));
        $this->assertSame('', $controller->exposeSafeFileName('   '));
        $this->assertSame(15, $controller->exposeLastEventIdHeader());
    }

    public function testJsonHelpersReturnExpectedBodies(): void
    {
        $error = $this->newController()->exposeJsonError('broken', 418);
        $ok = $this->newController()->exposeJsonOk(['ok' => true, 'value' => 1]);
        $wrapped = $this->newController()->exposeUnwrapResult(['error' => 'broken', 'status' => 409]);

        $this->assertSame(418, $error->getStatusCode());
        $this->assertSame(['error' => 'broken'], $this->jsonBody($error));
        $this->assertSame(['ok' => true, 'value' => 1], $this->jsonBody($ok));
        $this->assertSame(409, $wrapped->getStatusCode());
    }

    public function testRequireTokenReadsSessionTokenHeader(): void
    {
        $request = clone service('request');
        $request->setHeader('X-ThatsApp-Token', 'token-123');

        $this->assertSame('token-123', $this->newController($request)->exposeRequireToken());
    }

    private function newController($request = null): ApiBaseControllerProxy
    {
        $controller = new ApiBaseControllerProxy();
        $controller->initController(
            $request ?? clone service('request'),
            clone service('response'),
            service('logger'),
        );
        return $controller;
    }

    private function jsonBody(ResponseInterface $response): array
    {
        return json_decode($response->getBody(), true, 512, JSON_THROW_ON_ERROR);
    }
}

final class ApiBaseControllerProxy extends ApiBaseController
{
    public function exposeRequireSessionName(string $sessionName): string|ResponseInterface
    {
        return $this->requireSessionName($sessionName);
    }

    public function exposeSafeFileName(string $name): string
    {
        return $this->safeFileName($name);
    }

    public function exposeLastEventIdHeader(string $headerName = 'X-Last-Event-ID'): int
    {
        return $this->lastEventIdHeader($headerName);
    }

    public function exposeJsonError(string $message, int $status): ResponseInterface
    {
        return $this->jsonError($message, $status);
    }

    public function exposeJsonOk(?array $payload = null): ResponseInterface
    {
        return $this->jsonOk($payload);
    }

    public function exposeUnwrapResult(array $result): array|ResponseInterface
    {
        return $this->unwrapResult($result);
    }

    public function exposeRequireToken(): string|ResponseInterface
    {
        return $this->requireToken();
    }
}
