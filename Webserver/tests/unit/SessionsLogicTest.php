<?php

use App\Controllers\Api\Sessions;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class SessionsLogicTest extends CIUnitTestCase
{
    private Sessions $controller;

    protected function setUp(): void
    {
        parent::setUp();
        $this->controller = new Sessions();
        $this->controller->initController(clone service('request'), clone service('response'), service('logger'));
    }

    public function testAccessKeyMatchingSupportsEmptyAndHashedKeys(): void
    {
        $hash = password_hash('secret', PASSWORD_DEFAULT);

        $this->assertTrue($this->invokePrivate('accessKeyMatches', '', ''));
        $this->assertFalse($this->invokePrivate('accessKeyMatches', 'plain', 'plain'));
        $this->assertFalse($this->invokePrivate('accessKeyMatches', 'plain', 'other'));
        $this->assertTrue($this->invokePrivate('accessKeyMatches', $hash, 'secret'));
    }

    public function testHashAccessKeyAndNonceCreation(): void
    {
        $hash = $this->invokePrivate('hashAccessKeyForStore', 'secret');
        $nonce = $this->invokePrivate('newNonce');

        $this->assertSame('', $this->invokePrivate('hashAccessKeyForStore', ''));
        $this->assertTrue(password_verify('secret', $hash));
        $this->assertNotEmpty(base64_decode($nonce, true));
    }

    private function invokePrivate(string $method, mixed ...$args): mixed
    {
        $reflection = new ReflectionMethod(Sessions::class, $method);
        $reflection->setAccessible(true);

        return $reflection->invoke($this->controller, ...$args);
    }
}
