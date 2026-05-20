<?php

namespace App\Support;

final class ApiEventTypes
{
    public const MESSAGE = 'MESSAGE';
    public const USER_JOIN = 'USER_JOIN';
    public const USER_LEAVE = 'USER_LEAVE';
    public const USER_TYPING = 'USER_TYPING';
    public const FILE_META = 'FILE_META';

    private function __construct()
    {
    }
}
