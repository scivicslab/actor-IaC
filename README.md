# actor-IaC

Infrastructure as Code (IaC) implementation using POJO-actor workflow engine.

[![Java Version](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## Overview

actor-IaC is an Infrastructure as Code tool built on top of the POJO-actor workflow engine. It enables declarative infrastructure management through YAML-based workflows.

## Requirements

- Java 21 or higher
- Maven 3.6+
- POJO-actor 2.6.0

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
