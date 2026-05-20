<?php

namespace App\Support;

final class TusStorage
{
    private const UPLOAD_KEY_PATTERN = '/\A[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}\z/';

    public static function uploadRoot(): string
    {
        return rtrim(WRITEPATH, '\\/') . '/uploads/tus';
    }

    public static function cacheRoot(): string
    {
        return rtrim(WRITEPATH, '\\/') . '/cache';
    }

    public static function uploadDir(string $uploadRoot, string $uploadKey): ?string
    {
        $key = trim($uploadKey);
        if (!self::isValidUploadKey($key)) {
            return null;
        }

        return rtrim($uploadRoot, '\\/') . '/' . $key;
    }

    public static function isValidUploadKey(string $uploadKey): bool
    {
        return $uploadKey !== '' && preg_match(self::UPLOAD_KEY_PATTERN, $uploadKey) === 1;
    }

    public static function deleteUploadFile(string $filePath, string $uploadRoot): void
    {
        if (is_file($filePath) && self::isUploadPath($filePath, $uploadRoot)) {
            @unlink($filePath);
        }

        self::cleanupDirectory($filePath, $uploadRoot);
    }

    public static function cleanupDirectory(string $filePath, string $uploadRoot): void
    {
        $root = realpath($uploadRoot);
        $dir = realpath(dirname($filePath));
        if ($root === false || $dir === false || !self::isDescendantPath($dir, $root)) {
            return;
        }

        $items = scandir($dir);
        if ($items !== false && count(array_diff($items, ['.', '..'])) === 0) {
            @rmdir($dir);
        }
    }

    public static function isUploadPath(string $path, string $uploadRoot): bool
    {
        $normalizedPath = realpath($path);
        $normalizedRoot = realpath($uploadRoot);
        if ($normalizedPath === false || $normalizedRoot === false) {
            return false;
        }

        return self::isDescendantPath($normalizedPath, $normalizedRoot);
    }

    private static function isDescendantPath(string $path, string $root): bool
    {
        $normalizedPath = self::normalizeSeparators(rtrim($path, '\\/'));
        $normalizedRoot = self::normalizeSeparators(rtrim($root, '\\/'));
        if ($normalizedPath === '' || $normalizedRoot === '' || $normalizedPath === $normalizedRoot) {
            return false;
        }

        return str_starts_with($normalizedPath, $normalizedRoot . '/');
    }

    private static function normalizeSeparators(string $path): string
    {
        return str_replace('\\', '/', $path);
    }
}
