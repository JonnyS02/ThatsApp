<?php

use App\Models\Api\ApiModel;
use CodeIgniter\Database\BaseBuilder;
use CodeIgniter\Test\CIUnitTestCase;
use Tests\Support\Database\TestConnection;

/**
 * @internal
 */
final class ApiModelTest extends CIUnitTestCase
{
    public function testUuidV4UsesExpectedFormat(): void
    {
        $db = new TestConnection([]);
        $model = new class($db) extends ApiModel {
            public function newUuid(): string
            {
                return $this->uuidV4();
            }
        };

        $uuid = $model->newUuid();

        $this->assertMatchesRegularExpression(
            '/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/',
            $uuid,
        );
    }

    public function testClaimEventStreamClaimsOpenSlot(): void
    {
        $builder = $this->getMockBuilder(BaseBuilder::class)
            ->disableOriginalConstructor()
            ->onlyMethods(['where', 'update'])
            ->getMock();
        $builder->expects($this->exactly(2))
            ->method('where')
            ->willReturnSelf();
        $builder->expects($this->once())
            ->method('update')
            ->with([
                'events_stream_open' => 1,
            ])
            ->willReturn(true);

        $db = $this->createMock(TestConnection::class);
        $db->expects($this->once())
            ->method('table')
            ->with('clients')
            ->willReturn($builder);
        $db->expects($this->once())
            ->method('affectedRows')
            ->willReturn(1);

        $model = new ApiModel($db);

        $this->assertTrue($model->claimEventStream(7));
    }

    public function testClaimEventStreamFailsWhenNoRowCanBeClaimed(): void
    {
        $builder = $this->getMockBuilder(BaseBuilder::class)
            ->disableOriginalConstructor()
            ->onlyMethods(['where', 'update'])
            ->getMock();
        $builder->expects($this->exactly(2))
            ->method('where')
            ->willReturnSelf();
        $builder->expects($this->once())
            ->method('update')
            ->with([
                'events_stream_open' => 1,
            ])
            ->willReturn(true);

        $db = $this->createMock(TestConnection::class);
        $db->expects($this->once())
            ->method('table')
            ->with('clients')
            ->willReturn($builder);
        $db->expects($this->once())
            ->method('affectedRows')
            ->willReturn(0);

        $model = new ApiModel($db);

        $this->assertFalse($model->claimEventStream(7));
    }
}
