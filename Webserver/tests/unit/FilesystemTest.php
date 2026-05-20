<?php

use App\Support\Filesystem;
use CodeIgniter\Test\CIUnitTestCase;

/**
 * @internal
 */
final class FilesystemTest extends CIUnitTestCase
{
    public function testDeleteDirRemovesNestedDirectories(): void
    {
        $root = sys_get_temp_dir() . '/thatsapp-filesystem-' . bin2hex(random_bytes(4));
        mkdir($root . '/nested/inner', 0777, true);
        file_put_contents($root . '/nested/file.txt', 'data');
        file_put_contents($root . '/nested/inner/file.txt', 'data');

        Filesystem::deleteDir($root);

        $this->assertDirectoryDoesNotExist($root);
    }

    public function testDeleteDirIgnoresMissingDirectories(): void
    {
        $missing = sys_get_temp_dir() . '/thatsapp-filesystem-missing-' . bin2hex(random_bytes(4));

        Filesystem::deleteDir($missing);

        $this->assertDirectoryDoesNotExist($missing);
    }
}
