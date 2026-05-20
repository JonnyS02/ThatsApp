<?php

namespace App\Validation;

final class StrictTypes
{
    public function strict_int($value): bool
    {
        return is_int($value);
    }

    public function strict_bool($value): bool
    {
        return is_bool($value);
    }
}
