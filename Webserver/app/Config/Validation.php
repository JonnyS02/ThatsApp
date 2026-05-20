<?php

namespace Config;

use App\Validation\StrictTypes;
use CodeIgniter\Config\BaseConfig;
use CodeIgniter\Validation\StrictRules\CreditCardRules;
use CodeIgniter\Validation\StrictRules\FileRules;
use CodeIgniter\Validation\StrictRules\FormatRules;
use CodeIgniter\Validation\StrictRules\Rules;

class Validation extends BaseConfig
{
    // --------------------------------------------------------------------
    // Setup
    // --------------------------------------------------------------------

    /**
     * Stores the classes that contain the
     * rules that are available.
     *
     * @var list<string>
     */
    public array $ruleSets = [
        Rules::class,
        FormatRules::class,
        FileRules::class,
        CreditCardRules::class,
        StrictTypes::class,
    ];

    /**
     * Specifies the views that are used to display the
     * errors.
     *
     * @var array<string, string>
     */
    public array $templates = [
        'list'   => 'CodeIgniter\Validation\Views\list',
        'single' => 'CodeIgniter\Validation\Views\single',
    ];

    // --------------------------------------------------------------------
    // Rules
    // --------------------------------------------------------------------

    public array $apiMessages = [
        'message' => 'field_exists|string',
    ];

    public array $apiMessages_errors = [
        'message' => [
            'field_exists' => 'Missing field: Message (message).',
            'string' => 'Invalid field: Message (message).',
        ],
    ];

    public array $apiTyping = [
        'payload' => 'field_exists|string',
    ];

    public array $apiTyping_errors = [
        'payload' => [
            'field_exists' => 'Missing field: Payload (payload).',
            'string' => 'Invalid field: Payload (payload).',
        ],
    ];

    public array $apiHandshake = [
        'accessKey' => 'field_exists|string',
        'autoCreate' => 'field_exists|strict_bool',
        'chatEncrypted' => 'field_exists|strict_bool',
    ];

    public array $apiHandshake_errors = [
        'accessKey' => [
            'field_exists' => 'Missing field: Access Key (accessKey).',
            'string' => 'Invalid field: Access Key (accessKey).',
        ],
        'autoCreate' => [
            'field_exists' => 'Missing field: Auto Create (autoCreate).',
            'strict_bool' => 'Invalid field: Auto Create (autoCreate).',
        ],
        'chatEncrypted' => [
            'field_exists' => 'Missing field: Chat Encrypted (chatEncrypted).',
            'strict_bool' => 'Invalid field: Chat Encrypted (chatEncrypted).',
        ],
    ];

    public array $apiConnect = [
        'accessKey' => 'field_exists|string',
        'name' => 'field_exists|string',
        'chatEncrypted' => 'field_exists|strict_bool',
    ];

    public array $apiConnect_errors = [
        'accessKey' => [
            'field_exists' => 'Missing field: Access Key (accessKey).',
            'string' => 'Invalid field: Access Key (accessKey).',
        ],
        'name' => [
            'field_exists' => 'Missing field: Name (name).',
            'string' => 'Invalid field: Name (name).',
        ],
        'chatEncrypted' => [
            'field_exists' => 'Missing field: Chat Encrypted (chatEncrypted).',
            'strict_bool' => 'Invalid field: Chat Encrypted (chatEncrypted).',
        ],
    ];

    public array $apiToken = [
        'token' => 'required|string',
    ];

    public array $apiToken_errors = [
        'token' => [
            'required' => 'Invalid token',
            'string' => 'Invalid token',
        ],
    ];
}
