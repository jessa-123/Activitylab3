package com.google.devtools.build.lib.authandtls.credentialhelper;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GetCredentialsRequest}. */
@RunWith(JUnit4.class)
public class GetCredentialsRequestTest {
  private static final Gson GSON = new Gson();

  @Test
  public void parseValid() {
    assertThat(
            GSON.fromJson("{\"uri\": \"http://example.com\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"https://example.com\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("https://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"grpc://example.com\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("grpc://example.com"));
    assertThat(
            GSON.fromJson("{\"uri\": \"grpcs://example.com\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("grpcs://example.com"));

    assertThat(
            GSON.fromJson("{\"uri\": \"uri-without-protocol\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("uri-without-protocol"));
  }

  @Test
  public void parseMissingUri() {
    assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("{}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"foo\": 1}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"foo\": 1, \"bar\": 2}", GetCredentialsRequest.class));
  }

  @Test
  public void parseNonStringUri() {
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": {}}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": []}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": [\"https://example.com\"]}", GetCredentialsRequest.class));
    assertThrows(
        JsonSyntaxException.class,
        () -> GSON.fromJson("{\"uri\": null}", GetCredentialsRequest.class));
  }

  @Test
  public void parseWithExtraFields() {
    assertThat(
            GSON.fromJson(
                    "{\"uri\": \"http://example.com\", \"foo\": 1}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"foo\": 1, \"uri\": \"http://example.com\"}", GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"uri\": \"http://example.com\", \"foo\": 1, \"bar\": {}}",
                    GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("http://example.com"));
    assertThat(
            GSON.fromJson(
                    "{\"foo\": 1, \"uri\": \"http://example.com\", \"bar\": []}",
                    GetCredentialsRequest.class)
                .getUri())
        .isEqualTo(URI.create("http://example.com"));
  }
}
