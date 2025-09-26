package com.example.agent;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

public class BedrockClientFactory {

    public static BedrockRuntimeClient create() {
        // Region bere z prostředí (AWS_REGION) nebo nastavte explicitně zde.
        return BedrockRuntimeClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
