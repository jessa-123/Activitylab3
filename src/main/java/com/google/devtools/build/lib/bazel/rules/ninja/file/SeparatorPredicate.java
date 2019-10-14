// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.rules.ninja.file;

import java.util.ListIterator;
import java.util.Optional;

/**
 * Defines the separation of the tokens.
 */
public interface SeparatorPredicate {

  /**
   * Determines if the current position of character iterator points to the beginning of the
   * separator, and the character sequence before should be a separate token.
   *
   * @param b byte to test
   * @param iterator points to the position after the passed byte and allows to look into the
   * sequence after that byte; should have the same value after exiting the method.
   * @return Optional, which contains true if the current position of the iterator is pointing
   * to the beginning of the separator, false if not, and is not set if iterator points
   * to the beginning of separator, but the buffer ends there and it is not enough information
   * to say if the token ends or continues.
   */
  Optional<Boolean> isSeparator(byte b, ListIterator<Byte> iterator);

  /**
   * Determines if two adjacent buffer fragments contain separate tokens.
   *
   * @param leftAtEnd left fragment iterator, possibly containing the beginning of token separator,
   * positioned behind the last symbol
   * @param rightAtBeginning right fragment iterator, possibly containing the end of token
   * separator, positioned at the start symbol
   * @return true if the fragments contain two separate tokens
   */
  boolean splitAdjacent(ListIterator<Byte> leftAtEnd,
      ListIterator<Byte> rightAtBeginning);
}
