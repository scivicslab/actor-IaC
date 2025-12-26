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

## Requirements

- Java 21 or higher
- Maven 3.6+
- POJO-actor 2.6.0
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

### Cluster Actor

`ActorRef<Cluster>` - Manages multiple nodes based on Ansible inventory files.

```java
ActorSystem system = new ActorSystem("iac", 4);
Cluster cluster = new Cluster(system);

// Load inventory
InputStream inventory = new FileInputStream("inventory.ini");
cluster.loadInventory(inventory);

// Create node actors for a group
List<ActorRef<Node>> webservers = cluster.createNodesForGroup("webservers");

// Execute commands on all nodes concurrently
List<CompletableFuture<Node.CommandResult>> futures = webservers.stream()
    .map(nodeActor -> nodeActor.ask(node ->
        node.executeCommand("uptime")
    ))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
