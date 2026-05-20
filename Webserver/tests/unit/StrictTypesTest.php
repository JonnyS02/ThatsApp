<?php

use App\Validation\StrictTypes;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class StrictTypesTest extends CIUnitTestCase
{
    public function testStrictIntOnlyAcceptsNativeIntegers(): void
    {
        $rules = new StrictTypes();

        $this->assertTrue($rules->strict_int(5));
        $this->assertFalse($rules->strict_int('5'));
        $this->assertFalse($rules->strict_int(5.0));
    }

    public function testStrictBoolOnlyAcceptsNativeBooleans(): void
    {
        $rules = new StrictTypes();

        $this->assertTrue($rules->strict_bool(true));
        $this->assertTrue($rules->strict_bool(false));
        $this->assertFalse($rules->strict_bool(1));
        $this->assertFalse($rules->strict_bool('true'));
    }
}
