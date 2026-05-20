<?php

use App\Models\Api\SessionsModel;
use CodeIgniter\Database\BaseBuilder;
use CodeIgniter\Database\ResultInterface;
use CodeIgniter\Test\CIUnitTestCase;
use Tests\Support\Database\TestConnection;

/**
 * @internal
 */
final class SessionsModelTest extends CIUnitTestCase
{
    public function testStalePreStreamClientIdsSkipsInvalidArguments(): void
    {
        $db = $this->createMock(TestConnection::class);
        $db->expects($this->never())
            ->method('table');

        $model = new SessionsModel($db);

        $this->assertSame([], $model->stalePreStreamClientIds(0, 30));
        $this->assertSame([], $model->stalePreStreamClientIds(7, 0));
    }

    public function testStalePreStreamClientIdsSelectsOnlyExpiredClosedStreams(): void
    {
        $whereCalls = [];
        $result = $this->createMock(ResultInterface::class);
        $result->expects($this->once())
            ->method('getResultArray')
            ->willReturn([
                ['id' => '3'],
                ['id' => 9],
            ]);

        $builder = $this->getMockBuilder(BaseBuilder::class)
            ->disableOriginalConstructor()
            ->onlyMethods(['select', 'where', 'get'])
            ->getMock();
        $builder->expects($this->once())
            ->method('select')
            ->with('id')
            ->willReturnSelf();
        $builder->expects($this->exactly(3))
            ->method('where')
            ->willReturnCallback(function (...$args) use (&$whereCalls, $builder) {
                $whereCalls[] = $args;
                return $builder;
            });
        $builder->expects($this->once())
            ->method('get')
            ->willReturn($result);

        $db = $this->createMock(TestConnection::class);
        $db->expects($this->once())
            ->method('table')
            ->with('clients')
            ->willReturn($builder);

        $model = new SessionsModel($db);

        $this->assertSame([3, 9], $model->stalePreStreamClientIds(7, 30));
        $this->assertSame('session_id', $whereCalls[0][0]);
        $this->assertSame(7, $whereCalls[0][1]);
        $this->assertSame('events_stream_open', $whereCalls[1][0]);
        $this->assertSame(0, $whereCalls[1][1]);
        $this->assertSame('created_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 SECOND)', $whereCalls[2][0]);
        $this->assertNull($whereCalls[2][1]);
        $this->assertFalse($whereCalls[2][2]);
    }
}
