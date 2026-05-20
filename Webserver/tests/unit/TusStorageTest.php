<?php

use App\Support\TusStorage;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class TusStorageTest extends CIUnitTestCase
{
    public function testBuildsExpectedStorageRoots(): void
    {
        $this->assertStringEndsWith('/uploads/tus', str_replace('\\', '/', TusStorage::uploadRoot()));
        $this->assertStringEndsWith('/cache', str_replace('\\', '/', TusStorage::cacheRoot()));
    }

    public function testRecognizesPathsInsideUploadRootOnly(): void
    {
        $root = sys_get_temp_dir() . '/thatsapp-tus-' . bin2hex(random_bytes(4));
        $uploadDir = $root . '/session';
        $outsideDir = $root . '-outside';
        mkdir($uploadDir, 0777, true);
        mkdir($outsideDir, 0777, true);
        $insideFile = $uploadDir . '/upload.bin';
        $outsideFile = $outsideDir . '/upload.bin';
        file_put_contents($insideFile, 'data');
        file_put_contents($outsideFile, 'data');

        $this->assertTrue(TusStorage::isUploadPath($insideFile, $root));
        $this->assertFalse(TusStorage::isUploadPath($root, $root));
        $this->assertFalse(TusStorage::isUploadPath($outsideFile, $root));

        unlink($insideFile);
        unlink($outsideFile);
        rmdir($uploadDir);
        rmdir($outsideDir);
        rmdir($root);
    }

    public function testBuildsUploadDirectoryOnlyForSafeKeys(): void
    {
        $root = 'C:/uploads/tus';

        $this->assertSame('C:/uploads/tus/upload-1', TusStorage::uploadDir($root, 'upload-1'));
        $this->assertNull(TusStorage::uploadDir($root, '../escape'));
        $this->assertNull(TusStorage::uploadDir($root, 'nested/upload'));
        $this->assertNull(TusStorage::uploadDir($root, ''));
    }

    public function testCleanupDirectoryRemovesEmptyUploadDirectoriesOnly(): void
    {
        $root = sys_get_temp_dir() . '/thatsapp-tus-' . bin2hex(random_bytes(4));
        $uploadDir = $root . '/session';
        mkdir($uploadDir, 0777, true);
        $file = $uploadDir . '/upload.bin';
        file_put_contents($file, 'data');
        unlink($file);

        TusStorage::cleanupDirectory($file, $root);

        $this->assertDirectoryDoesNotExist($uploadDir);
    }

    public function testDeleteUploadFileIgnoresOutsidePaths(): void
    {
        $root = sys_get_temp_dir() . '/thatsapp-tus-' . bin2hex(random_bytes(4));
        $outsideDir = sys_get_temp_dir() . '/thatsapp-outside-' . bin2hex(random_bytes(4));
        mkdir($root, 0777, true);
        mkdir($outsideDir, 0777, true);
        $outsideFile = $outsideDir . '/upload.bin';
        file_put_contents($outsideFile, 'data');

        TusStorage::deleteUploadFile($outsideFile, $root);

        $this->assertFileExists($outsideFile);

        unlink($outsideFile);
        rmdir($outsideDir);
        rmdir($root);
    }

    public function testDeleteUploadFileRemovesEmptyUploadDirectoryForMissingFile(): void
    {
        $root = sys_get_temp_dir() . '/thatsapp-tus-' . bin2hex(random_bytes(4));
        $uploadDir = $root . '/session';
        mkdir($uploadDir, 0777, true);
        $file = $uploadDir . '/upload.bin';

        TusStorage::deleteUploadFile($file, $root);

        $this->assertDirectoryDoesNotExist($uploadDir);
        rmdir($root);
    }
}
