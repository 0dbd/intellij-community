/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.autodetect;

import com.intellij.JavaTestUtil;
import org.jetbrains.annotations.NotNull;

public class NewLineBlocksIteratorTest extends AbstractNewLineBlocksIteratorTest {

  @NotNull
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath() +
           "/psi/autodetect/";
  }

  public void testSimpleFileNewBlockOffsetDetection() {
    configureByFile(getFileName() + ".java");

    int[] newLineBlocksStartOffsets = new int[] {
       0, 3, 30, 60, 82, 107, 136, 154, 184, 220, 226, 232, 237
    };

    checkNewLineBlocksStartOffsets(newLineBlocksStartOffsets);
  }

  public void testDoNotReverseBlocks() {
    configureByFile(getFileName() + ".xml");

    int[] newLineBlocksStartOffsets = new int[] {
      0, 39, 48, 118, 177, 235, 243
    };

    checkNewLineBlocksStartOffsets(newLineBlocksStartOffsets);
  }

}
