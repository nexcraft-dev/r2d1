package dev.nexcraft.r2d1.quarkus.it;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Application explicitly registers document metadata and record accessors for native images. */
@RegisterForReflection
@Document("quarkus_smoke")
public record SmokeDocument(@Id String id, @Index String country, String name) {}
