# actor-IaC

Infrastructure as Code (IaC) implementation using POJO-actor workflow engine.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Overview

actor-IaC is an Infrastructure as Code tool built on top of the POJO-actor workflow engine. It enables concurrent infrastructure management using the actor model with Ansible-compatible inventory files.

### Key Features

- **Ansible Inventory Compatibility**: Uses standard Ansible inventory file format (INI)
- **Concurrent Execution**: Execute commands on multiple nodes simultaneously using POJO-actor
- **SSH-Based Communication**: Each node actor executes commands via SSH
- **Lightweight**: No persistent connections - SSH sessions are created per command
- **Type-Safe**: Fully type-safe actor-based API using `ActorRef<Node>` and `ActorRef<Cluster>`
- **HashiCorp Vault Integration**: Secure secret management for SSH keys and sudo passwords
- **Sudo Support**: Execute commands with sudo privileges using Vault-managed passwords
- **H2 Database Logging**: Persistent execution logs with session management
- **Log Server Mode**: Centralized logging for parallel workflow execution
- **CLI Tool**: Full command-line interface for workflow execution and log management

## Requirements

- Java 21 or higher
- Maven 3.6+
- POJO-actor 2.10.0
- SSH access to target nodes

## Core Components

### Node Actor

`ActorRef<Node>` - Represents a single infrastructure node with SSH access.

```java
Node node = new Node("web1.example.com", "admin", 22, null);
ActorRef<Node> nodeActor = actorSystem.actorOf("web1", node);

// Execute command asynchronously
CompletableFuture<Node.CommandResult> result = nodeActor.ask(n ->
    n.executeCommand("hostname")
);

Node.CommandResult cmdResult = result.get();
System.out.println("Output: " + cmdResult.getStdout());
System.out.println("Exit code: " + cmdResult.getExitCode());
```

### NodeGroup

`NodeGroup` - A pure POJO that manages multiple nodes based on Ansible inventory files.

**POJO-actor Design Philosophy**: Following POJO-actor principles, there are three ways to use actor-IaC:

#### Level 1: Pure POJO (No Actor Framework)

**No `ActorSystem`, no `ActorRef`** - POJOs work as plain Java objects. Direct synchronous execution.

```java
// Create node group using Builder pattern
NodeGroup nodeGroup = new NodeGroup.Builder()
    .withInventory(new FileInputStream("inventory.ini"))
    .build();

// Create Node POJOs
List<Node> nodes = nodeGroup.createNodesForGroup("webservers");

// Use POJOs directly - synchronous, sequential execution
for (Node node : nodes) {
    try {
        Node.CommandResult result = node.executeCommand("uptime");
        System.out.println(node.getHostname() + ": " + result.getStdout());
    } catch (IOException e) {
        e.printStackTrace();
    }
}
```

#### Level 2: Actor-Based (Concurrent Execution)

**Using `ActorSystem` + `ActorRef`** - Convert POJOs to actors for asynchronous, concurrent execution.

```java
// Create node group and Node POJOs (same as Level 1)
NodeGroup nodeGroup = new NodeGroup.Builder()
    .withInventory(new FileInputStream("inventory.ini"))
    .build();

List<Node> nodes = nodeGroup.createNodesForGroup("webservers");

// Convert Node POJOs to actors
ActorSystem system = new ActorSystem("iac", 4);
List<ActorRef<Node>> actors = nodes.stream()
    .map(node -> system.actorOf("node-" + node.getHostname(), node))
    .toList();

// Execute concurrently using actor model
List<CompletableFuture<Node.CommandResult>> futures = actors.stream()
    .map(actor -> actor.ask(node -> {
        try {
            return node.executeCommand("uptime");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// Process results
for (int i = 0; i < futures.size(); i++) {
    Node.CommandResult result = futures.get(i).get();
    System.out.println(result.getStdout());
}

system.terminate();
```

#### Level 3: Workflow (YAML/JSON/XML)

Using `IIActorSystem` + `IIActorRef` + `Interpreter`, define infrastructure operations declaratively in YAML, JSON, or XML. The workflow engine (actor-WF) executes actions through state transitions, providing Turing-complete expressiveness.

```yaml
# deploy-workflow.yaml
name: deploy-webservers
steps:
  - states: ["0", "1"]
    actions:
      - actor: nodeGroup
        method: createNodeActors
        arguments: ["webservers"]
  - states: ["1", "2"]
    actions:
      - actor: node-web1.example.com
        method: executeCommand
        arguments: ["apt-get update"]
  - states: ["2", "end"]
    actions:
      - actor: node-web1.example.com
        method: executeCommand
        arguments: ["systemctl restart nginx"]
```

```java
// Create IIActorSystem for workflow execution
IIActorSystem system = new IIActorSystem("iac-workflow", 4);

// Create NodeGroup and wrap with IIActorRef
NodeGroup nodeGroup = new NodeGroup.Builder()
    .withInventory(new FileInputStream("inventory.ini"))
    .build();
NodeGroupIIAR nodeGroupActor = new NodeGroupIIAR("nodeGroup", nodeGroup, system);
system.addIIActor(nodeGroupActor);

// Create and configure Interpreter
Interpreter interpreter = new Interpreter.Builder()
    .loggerName("deploy-workflow")
    .team(system)
    .build();

// Load and execute workflow
interpreter.readYaml(new FileInputStream("deploy-workflow.yaml"));
ActionResult result = interpreter.runUntilEnd(100);

System.out.println("Result: " + result.getResult());
system.terminate();
```

### Inventory File Format

actor-IaC uses standard Ansible inventory format with support for:
- Host groups
- Global variables (`[all:vars]`)
- Group-specific variables (`[groupname:vars]`)
- Host-specific variables (inline with hostname)

**Variable Priority:** Host vars > Group vars > Global vars

```ini
[webservers]
web1.example.com ansible_user=webadmin
web2.example.com ansible_port=2222

[dbservers]
db1.example.com ansible_user=postgres ansible_port=5432
db2.example.com

[all:vars]
ansible_user=admin
ansible_port=22

[webservers:vars]
http_port=8080
```

In this example:
- `web1.example.com` uses `ansible_user=webadmin` (host-specific)
- `web2.example.com` uses `ansible_user=admin` (global) and `ansible_port=2222` (host-specific)
- `db1.example.com` uses both host-specific user and port
- `db2.example.com` uses global variables for both

## Building

```bash
# Compile and test
mvn clean test

# Build JAR
mvn clean package
```

## Command Line Interface

actor-IaC provides a comprehensive CLI for workflow execution and log management.

### Quick Start

```bash
# Run a workflow
./actor_iac.java run -d ./workflows -w deploy -i inventory.ini -g webservers

# List available workflows
./actor_iac.java list -d ./workflows

# Search execution logs
./actor_iac.java log-search --db ./logs/actor-iac-logs --list
```

### Commands

| Command | Description |
|---------|-------------|
| `run` | Execute a workflow |
| `list` | List available workflows in a directory |
| `describe` | Display workflow structure and transitions |
| `log-search` | Search and query execution logs |
| `log-serve` | Start H2 TCP server for centralized logging |
| `log-merge` | Merge scattered log databases into one |

### run - Execute Workflow

```bash
./actor_iac.java run -d <dir> -w <workflow> [options]
```

**Options:**
- `-d, --dir` : Directory containing workflow files (required)
- `-w, --workflow` : Workflow file name without extension (required)
- `-i, --inventory` : Ansible inventory file path
- `-g, --group` : Target host group from inventory
- `-o, --overlay` : YAML overlay file for environment-specific config
- `--log-db` : H2 database path for logging (default: `<workflow-dir>/actor-iac-logs`)
- `--log-serve` : Connect to H2 log server (e.g., `localhost:29090`)
- `-v, --verbose` : Enable verbose output

**Examples:**
```bash
# Basic workflow execution
./actor_iac.java run -d ./workflows -w deploy

# With inventory and group
./actor_iac.java run -d ./workflows -w deploy -i inventory.ini -g webservers

# With overlay for production
./actor_iac.java run -d ./workflows -w deploy -o overlays/production.yaml

# With centralized log server
./actor_iac.java run -d ./workflows -w deploy --log-serve=localhost:29090
```

### log-serve - Centralized Log Server

Start an H2 TCP server for centralized logging from multiple parallel workflows.

```bash
./actor_iac.java log-serve --db <path> [options]
```

**Options:**
- `--db` : Database file path without extension (default: `./actor-iac-logs`)
- `-p, --port` : TCP port (default: `29090`)
- `--find` : Scan for running log servers on localhost
- `-v, --verbose` : Enable verbose output

**Examples:**
```bash
# Start log server
./actor_iac.java log-serve --db ./logs/shared-logs

# Start on custom port
./actor_iac.java log-serve --db ./logs/shared-logs --port 29091

# Find running log servers
./actor_iac.java log-serve --find
```

**Architecture:**
```
[Operation Terminal]
├── H2 Log Server (localhost:29090)
├── Workflow Process A ──→ TCP write
├── Workflow Process B ──→ TCP write
└── Workflow Process C ──→ TCP write
```

### log-search - Query Logs

Search and display execution logs from the H2 database.

```bash
./actor_iac.java log-search --db <path> [options]
```

**Options:**
- `--db` : H2 database path (required)
- `--server` : H2 log server address (e.g., `localhost:29090`)
- `-s, --session` : Session ID to query (default: latest)
- `-n, --node` : Filter by node ID
- `--level` : Minimum log level (DEBUG, INFO, WARN, ERROR)
- `--list` : List recent sessions
- `--list-nodes` : List nodes in a session
- `--summary` : Show session summary only
- `-w, --workflow` : Filter sessions by workflow name
- `--since` : Filter sessions started within duration (e.g., `12h`, `1d`, `1w`)
- `--limit` : Maximum entries to show (default: 100)

**Examples:**
```bash
# List all sessions
./actor_iac.java log-search --db ./logs/actor-iac-logs --list

# Show logs for latest session
./actor_iac.java log-search --db ./logs/actor-iac-logs

# Filter by node and level
./actor_iac.java log-search --db ./logs/actor-iac-logs -s 42 -n web1.example.com --level ERROR

# Query via log server
./actor_iac.java log-search --server=localhost:29090 --db ./logs/actor-iac-logs --list

# Sessions from last 24 hours
./actor_iac.java log-search --db ./logs/actor-iac-logs --list --since 1d
```

### log-merge - Consolidate Logs

Merge scattered log databases into a single database.

```bash
./actor_iac.java log-merge --scan <dir> --target <path> [options]
```

**Options:**
- `--scan` : Directory to scan for .mv.db files (required)
- `--target` : Target database path (required)
- `--dry-run` : Preview without making changes
- `--skip-duplicates` : Skip sessions that already exist in target

**Examples:**
```bash
# Preview merge
./actor_iac.java log-merge --scan ./workflows --target ./logs/merged --dry-run

# Merge all logs
./actor_iac.java log-merge --scan ./workflows --target ./logs/merged

# Skip duplicates
./actor_iac.java log-merge --scan ./workflows --target ./logs/merged --skip-duplicates
```

## Installation

First, install POJO-actor to your local repository:

```bash
git clone https://github.com/scivicslab/POJO-actor
cd POJO-actor
./mvnw install
```

Then build actor-IaC:

```bash
cd ../actor-IaC
mvn clean package
```

## HashiCorp Vault Integration

actor-IaC supports secure secret management through HashiCorp Vault integration. This enables autonomous infrastructure management without using NOPASSWD sudo or storing credentials in files.

### Why Vault?

- **Security**: Centralized secret management with encryption at rest and in transit
- **Access Control**: Fine-grained access policies
- **Audit Logging**: Track all secret access
- **Autonomous Operation**: No human intervention needed for credential management
- **No NOPASSWD sudo**: Securely provide sudo passwords without security risks

### Configuration Files

Vault integration uses two configuration files:

1. **inventory.ini** - Standard Ansible inventory (no modifications needed)
2. **vault-config.ini** - Vault path configuration (actor-IaC specific)

### vault-config.ini Format

```ini
# Global Vault paths (applies to all hosts)
[vault:all]
ssh_key_path=secret/data/ssh/iacuser/private_key
sudo_password_path=secret/data/sudo/iacuser/password

# Group-specific Vault paths
[vault:webservers]
ssh_key_path=secret/data/ssh/webadmin/private_key
sudo_password_path=secret/data/sudo/webadmin/password

# Host-specific Vault paths (highest priority)
[vault:host:web1.example.com]
ssh_key_path=secret/data/ssh/web1-admin/private_key
sudo_password_path=secret/data/sudo/web1-admin/password
```

**Path Priority:** Host-specific > Group > Global

### Vault Setup

```bash
# Start Vault server (development mode)
vault server -dev

# Set environment variables
export VAULT_ADDR='http://127.0.0.1:8200'
export VAULT_TOKEN='dev-token'

# Store secrets in Vault
vault kv put secret/ssh/iacuser/private_key value=@~/.ssh/id_rsa
vault kv put secret/sudo/iacuser/password value="MySudoPassword"
```

### Usage Example with Vault

#### Using Builder Pattern (Recommended)

```java
// Initialize Vault client
VaultConfig vaultConfig = new VaultConfig(
    System.getenv("VAULT_ADDR"),
    System.getenv("VAULT_TOKEN")
);
VaultClient vaultClient = new VaultClient(vaultConfig);

// Create node group with Vault integration using Builder
NodeGroup nodeGroup = new NodeGroup.Builder()
    .withInventory(new FileInputStream("inventory.ini"))
    .withVaultConfig(new FileInputStream("vault-config.ini"), vaultClient)
    .build();

// Create Node objects (automatically fetches secrets from Vault)
List<Node> nodes = nodeGroup.createNodesForGroup("webservers");

// Convert to actors
ActorSystem system = new ActorSystem("iac", 4);
List<ActorRef<Node>> webservers = nodes.stream()
    .map(node -> system.actorOf("node-" + node.getHostname(), node))
    .toList();

// Execute sudo command (uses Vault-provided password)
CompletableFuture<Node.CommandResult> result = webservers.get(0).ask(node -> {
    try {
        return node.executeSudoCommand("apt-get update");
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
});

Node.CommandResult cmdResult = result.get();
System.out.println("Exit code: " + cmdResult.getExitCode());
System.out.println("Output: " + cmdResult.getStdout());
```

#### Legacy Constructor Pattern

```java
// Create node group with Vault
NodeGroup nodeGroup = new NodeGroup(vaultClient);

// Load inventory and vault configuration
nodeGroup.loadInventory(new FileInputStream("inventory.ini"));
nodeGroup.loadVaultConfig(new FileInputStream("vault-config.ini"));

// Create Node objects...
```

### Security Best Practices

1. **Never hardcode tokens**: Use environment variables or secure token providers
2. **Use AppRole authentication**: For production environments
3. **Apply least privilege**: Vault policies should grant minimum required permissions
4. **Enable audit logging**: Track all secret access
5. **Rotate secrets regularly**: Use Vault's secret rotation features

### Vault Policy Example

```hcl
# Minimal permissions for actor-IaC
path "secret/data/ssh/*" {
  capabilities = ["read"]
}

path "secret/data/sudo/*" {
  capabilities = ["read"]
}
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
