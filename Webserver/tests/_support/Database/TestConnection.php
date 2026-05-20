<?php

namespace Tests\Support\Database;

use CodeIgniter\Database\BaseConnection;
use stdClass;

class TestConnection extends BaseConnection
{
    public function connect(bool $persistent = false)
    {
        return false;
    }

    public function setDatabase(string $databaseName)
    {
        return true;
    }

    public function getVersion(): string
    {
        return 'test';
    }

    public function affectedRows(): int
    {
        return 0;
    }

    public function error(): array
    {
        return [
            'code' => 0,
            'message' => null,
        ];
    }

    public function insertID()
    {
        return 0;
    }

    protected function _close(): void
    {
    }

    protected function execute(string $sql)
    {
        return false;
    }

    protected function _transBegin(): bool
    {
        return true;
    }

    protected function _transCommit(): bool
    {
        return true;
    }

    protected function _transRollback(): bool
    {
        return true;
    }

    protected function _listTables(bool $constrainByPrefix = false, ?string $tableName = null)
    {
        return false;
    }

    protected function _listColumns($table = '')
    {
        return false;
    }

    protected function _fieldData(string $table): array
    {
        return [];
    }

    protected function _indexData(string $table): array
    {
        return [];
    }

    protected function _foreignKeyData(string $table): array
    {
        return [];
    }
}
