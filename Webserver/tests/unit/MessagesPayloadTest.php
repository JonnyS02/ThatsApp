<?php

use App\Controllers\Api\Messages;
use App\Support\ApiErrorMessages;
use CodeIgniter\HTTP\ResponseInterface;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class MessagesPayloadTest extends CIUnitTestCase
{
    private Messages $controller;

    protected function setUp(): void
    {
        parent::setUp();
        $this->controller = new Messages();
        $this->controller->initController(service('request'), service('response'), service('logger'));
    }

    public function testCountsPlainAndEncryptedPayloadCharacters(): void
    {
        $plain = $this->invokePrivate('countPayloadCharacters', 'P2:Hi 😀');
        $encryptedBytes = str_repeat("\0", 12 + 8 + 16);
        $encrypted = $this->invokePrivate('countPayloadCharacters', 'E2:' . base64_encode($encryptedBytes));

        $this->assertSame(4, $plain);
        $this->assertSame(2, $encrypted);
    }

    public function testRejectsPayloadWithWrongEncryptionMode(): void
    {
        $response = $this->invokePrivate('validateMessagePayload', 'P2:Hello', 10, true);

        $this->assertInstanceOf(ResponseInterface::class, $response);
        $this->assertSame(400, $response->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, $this->jsonBody($response)['error']);
    }

    public function testRejectsPayloadThatExceedsLimit(): void
    {
        $response = $this->invokePrivate('validateMessagePayload', 'P2:Hello', 4, false);

        $this->assertInstanceOf(ResponseInterface::class, $response);
        $this->assertSame(413, $response->getStatusCode());
        $this->assertSame(ApiErrorMessages::messageExceedsLimit(4), $this->jsonBody($response)['error']);
    }

    public function testAcceptsValidPayload(): void
    {
        $this->assertNull($this->invokePrivate('validateMessagePayload', 'P2:Hi', 10, false));
    }

    public function testAcceptsValidPlainTypingPayload(): void
    {
        $this->assertNull($this->invokePrivate('validateTypingPayload', '1500', false));
        $this->assertNull($this->invokePrivate('validateTypingPayload', '0', false));
    }

    public function testRejectsInvalidPlainTypingPayload(): void
    {
        $response = $this->invokePrivate('validateTypingPayload', 'typing', false);

        $this->assertInstanceOf(ResponseInterface::class, $response);
        $this->assertSame(400, $response->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_TYPING_PAYLOAD, $this->jsonBody($response)['error']);
    }

    public function testAcceptsValidEncryptedTypingPayload(): void
    {
        $payload = base64_encode(str_repeat("\0", 29));

        $this->assertNull($this->invokePrivate('validateTypingPayload', $payload, true));
    }

    public function testRejectsInvalidEncryptedTypingPayload(): void
    {
        $invalidBase64 = $this->invokePrivate('validateTypingPayload', 'not-base64', true);
        $tooShort = $this->invokePrivate('validateTypingPayload', base64_encode(str_repeat("\0", 28)), true);
        $tooLong = $this->invokePrivate('validateTypingPayload', base64_encode(str_repeat("\0", 65)), true);

        $this->assertInstanceOf(ResponseInterface::class, $invalidBase64);
        $this->assertSame(400, $invalidBase64->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_TYPING_PAYLOAD, $this->jsonBody($invalidBase64)['error']);

        $this->assertInstanceOf(ResponseInterface::class, $tooShort);
        $this->assertSame(400, $tooShort->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_TYPING_PAYLOAD, $this->jsonBody($tooShort)['error']);

        $this->assertInstanceOf(ResponseInterface::class, $tooLong);
        $this->assertSame(400, $tooLong->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_TYPING_PAYLOAD, $this->jsonBody($tooLong)['error']);
    }

    public function testTreatsZeroLimitAsUnlimited(): void
    {
        $this->assertNull($this->invokePrivate('validateMessagePayload', 'P2:Hello', 0, false));
    }

    public function testRejectsPayloadWithUnknownPrefix(): void
    {
        $response = $this->invokePrivate('validateMessagePayload', 'hello', 10, false);

        $this->assertInstanceOf(ResponseInterface::class, $response);
        $this->assertSame(400, $response->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, $this->jsonBody($response)['error']);
    }

    public function testRejectsEncryptedPayloadWithInvalidEncoding(): void
    {
        $invalidBase64 = $this->invokePrivate('validateMessagePayload', 'E2:not-base64', 10, true);
        $invalidLength = $this->invokePrivate('validateMessagePayload', 'E2:' . base64_encode(str_repeat("\0", 29)), 10, true);

        $this->assertInstanceOf(ResponseInterface::class, $invalidBase64);
        $this->assertSame(400, $invalidBase64->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, $this->jsonBody($invalidBase64)['error']);
        $this->assertInstanceOf(ResponseInterface::class, $invalidLength);
        $this->assertSame(400, $invalidLength->getStatusCode());
        $this->assertSame(ApiErrorMessages::INVALID_MESSAGE_PAYLOAD, $this->jsonBody($invalidLength)['error']);
    }

    private function invokePrivate(string $method, mixed ...$args): mixed
    {
        $reflection = new ReflectionMethod(Messages::class, $method);
        $reflection->setAccessible(true);

        return $reflection->invoke($this->controller, ...$args);
    }

    private function jsonBody(ResponseInterface $response): array
    {
        return json_decode($response->getBody(), true, 512, JSON_THROW_ON_ERROR);
    }
}
