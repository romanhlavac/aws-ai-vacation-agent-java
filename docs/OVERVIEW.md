# AWS AI Vacation Agent – Codebase Overview

This document is a guided tour of the repository to help new contributors get productive quickly.

## High-level architecture

The project implements a single AWS Lambda function (written in Java 21) that is exposed through Amazon API Gateway. The Lambda receives chat-style requests, asks Amazon Bedrock to interpret the user’s intent (when necessary), fetches a seven-day forecast from Open-Meteo, and returns a structured JSON response. The infrastructure is defined using AWS SAM, so the application and the infrastructure are deployed together.

Key components:

- **Application code** lives under [`src/main/java/com/example/agent`](../src/main/java/com/example/agent). The entry point is `AgentHandler`, which orchestrates request parsing, Bedrock invocation, geocoding, forecasting, and response formatting.
- **Infrastructure as code** is described in [`template.yaml`](../template.yaml). It defines the Lambda function, the API Gateway endpoint, environment variables, and deployment parameters.
- **Build tooling** is driven by [`pom.xml`](../pom.xml), which configures Maven dependencies and the Shade plugin to produce a self-contained deployment artifact.
- **Deployment helpers** like [`Deploy.sh`](../Deploy.sh) and [`samconfig.toml`](../samconfig.toml) capture example CLI commands and default SAM parameters for repeatable deployments.

## Application flow (`AgentHandler`)

[`AgentHandler`](../src/main/java/com/example/agent/AgentHandler.java) implements the `RequestHandler` interface for API Gateway proxy events. The handler executes the following steps:

1. **Parse the request body** into a `ChatRequest` POJO (`Models.ChatRequest`).
2. **Resolve the destination**: prefer the explicit `destination` field, otherwise attempt extraction from the free-form `message` using a Bedrock model specified by the `BEDROCK_MODEL_ID` environment variable. If no model ID is configured, a heuristic fallback looks for capitalised words such as "do Praha".
3. **Handle missing destinations** by returning a prompt asking the user to specify one (`ChatResponse.askForDestination`).
4. **Geocode the destination** via Open-Meteo’s `/search` endpoint and error out gracefully if the place cannot be resolved.
5. **Fetch the forecast** for the latitude/longitude using Open-Meteo’s forecast API.
6. **Format a summary string** with daily max/min temperatures and precipitation while also returning the raw forecast payload for clients that want to post-process it.

Supporting classes:

- [`Models`](../src/main/java/com/example/agent/Models.java) contains the request/response DTOs for the chat interaction plus POJOs for the geocoding and forecast APIs. Jackson annotations ensure safe JSON binding.
- [`BedrockClientFactory`](../src/main/java/com/example/agent/BedrockClientFactory.java) configures the AWS SDK v2 `BedrockRuntimeClient` using default credentials and the region supplied via `AWS_REGION`.
- [`HttpUtil`](../src/main/java/com/example/agent/HttpUtil.java) wraps Java’s `HttpClient` with convenience helpers for performing HTTP GETs against Open-Meteo.

## Infrastructure and configuration

The SAM template defines:

- A parameterised `BedrockModelId` that is injected into the Lambda as the `BEDROCK_MODEL_ID` environment variable.
- A mandatory `ExecutionRoleArn` parameter so you can supply an IAM role with Bedrock invocation permissions at deploy time.
- Shared function defaults (runtime, memory, timeout, tracing) under `Globals`.
- An API Gateway REST API exposing `POST /chat` mapped to the Lambda handler.

`Deploy.sh` demonstrates the non-guided deployment workflow: preparing S3 buckets, cleaning up prior stacks, running `sam deploy`, and exercising the API with `curl`.

## Build & local tooling

The project uses Maven with Java 21. The Shade plugin produces a fat JAR during `mvn package`, which SAM uses during `sam build`. `mvnw` and `mvnw.cmd` wrappers are included for consistent builds.

For iterative development you can:

- Run `mvn test` (no unit tests yet) or `mvn package` locally.
- Use `sam local invoke` with a sample payload to simulate the Lambda (provide mocked environment variables or stub network calls).
- Configure environment variables such as `BEDROCK_MODEL_ID` and `AWS_REGION` when invoking locally to mimic the cloud setup.

## Next steps and learning pointers

- **Error handling & resiliency**: add structured logging, retries, and tighter exception handling around external HTTP calls.
- **Testing**: introduce unit tests for destination extraction logic and forecast formatting; consider contract tests against mock Open-Meteo/Bedrock services.
- **Observability**: extend CloudWatch logging/metrics, or integrate with AWS X-Ray (tracing is already enabled globally).
- **Security**: explore securing the API Gateway endpoint (API keys, IAM auth, Cognito) and rate limiting.
- **Extensibility**: encapsulate Bedrock prompts and weather summarisation in separate services to support multiple languages or additional data sources.

