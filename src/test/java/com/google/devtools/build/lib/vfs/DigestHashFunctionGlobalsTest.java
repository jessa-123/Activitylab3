// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.vfs.DigestHashFunction.DefaultHashFunctionNotSetException;
import com.google.devtools.build.lib.vfs.DigestHashFunction.DigestFunctionConverter;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for DigestHashFunction, notably that the static instances can be compared with reference
 * equality.
 */
@RunWith(JUnit4.class)
public class DigestHashFunctionGlobalsTest {
  private final DigestFunctionConverter converter = new DigestFunctionConverter();

  @Before
  public void resetStaticDefault() throws IllegalAccessException, NoSuchFieldException {
    // The default is effectively a Singleton, and it does not allow itself to be set multiple
    // times. In order to test this reasonably, though, we reset the value to null,
    // as it is before setDefault is called.

    Field defaultValue = DigestHashFunction.class.getDeclaredField("defaultHash");
    defaultValue.setAccessible(true);
    defaultValue.set(null, null);
  }

  @Test
  public void convertReturnsTheSameValueAsTheConstant() throws Exception {
    assertThat(converter.convert("sha-512")).isSameInstanceAs(DigestHashFunction.SHA512);
    assertThat(converter.convert("SHA-512")).isSameInstanceAs(DigestHashFunction.SHA512);
    assertThat(converter.convert("SHA512")).isSameInstanceAs(DigestHashFunction.SHA512);
    assertThat(converter.convert("sha512")).isSameInstanceAs(DigestHashFunction.SHA512);

    assertThat(converter.convert("sha-384")).isSameInstanceAs(DigestHashFunction.SHA384);
    assertThat(converter.convert("SHA-384")).isSameInstanceAs(DigestHashFunction.SHA384);
    assertThat(converter.convert("SHA384")).isSameInstanceAs(DigestHashFunction.SHA384);
    assertThat(converter.convert("sha384")).isSameInstanceAs(DigestHashFunction.SHA384);

    assertThat(converter.convert("sha-256")).isSameInstanceAs(DigestHashFunction.SHA256);
    assertThat(converter.convert("SHA-256")).isSameInstanceAs(DigestHashFunction.SHA256);
    assertThat(converter.convert("SHA256")).isSameInstanceAs(DigestHashFunction.SHA256);
    assertThat(converter.convert("sha256")).isSameInstanceAs(DigestHashFunction.SHA256);

    assertThat(converter.convert("SHA-1")).isSameInstanceAs(DigestHashFunction.SHA1);
    assertThat(converter.convert("sha-1")).isSameInstanceAs(DigestHashFunction.SHA1);
    assertThat(converter.convert("SHA1")).isSameInstanceAs(DigestHashFunction.SHA1);
    assertThat(converter.convert("sha1")).isSameInstanceAs(DigestHashFunction.SHA1);
  }

  @Test
  public void lateRegistrationGetsPickedUpByConverter() throws Exception {
    DigestHashFunction.register(Hashing.goodFastHash(32), "SHA-42");

    assertThat(converter.convert("SHA-42")).isSameInstanceAs(converter.convert("sha-42"));
  }

  @Test
  public void lateRegistrationWithAlternativeNamesGetsPickedUpByConverter() throws Exception {
    DigestHashFunction.register(Hashing.goodFastHash(64), "SHA-123", "SHA123", "SHA_123");

    assertThat(converter.convert("SHA-123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("Sha-123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("sha-123")).isSameInstanceAs(converter.convert("SHA-123"));

    assertThat(converter.convert("SHA123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("Sha123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("sha123")).isSameInstanceAs(converter.convert("SHA-123"));

    assertThat(converter.convert("SHA_123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("Sha_123")).isSameInstanceAs(converter.convert("SHA-123"));
    assertThat(converter.convert("sha_123")).isSameInstanceAs(converter.convert("SHA-123"));
  }

  @Test
  public void unsetDefaultThrows() {
    assertThrows(DefaultHashFunctionNotSetException.class, () -> DigestHashFunction.getDefault());
  }

  @Test
  public void setDefaultDoesNotThrow() throws Exception {
    DigestHashFunction.setDefault(DigestHashFunction.SHA1);
    DigestHashFunction.getDefault();
  }

  @Test
  public void cannotSetDefaultMultipleTimes() throws Exception {
    DigestHashFunction.setDefault(DigestHashFunction.SHA256);
    assertThrows(
        DigestHashFunction.DefaultAlreadySetException.class,
        () -> DigestHashFunction.setDefault(DigestHashFunction.SHA1));
  }
}
