/*
 Copyright 2026 JKU/Dynatrace Co-Innovation Lab

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package com.dynatrace.regex.utils;

/** Splits UTF-8 encoded text into lines the way {@link String#lines()} does, without copying any bytes. */
public final class ByteLines {
    private ByteLines() {}

    /** Receives one line as the range {@code [offset, offset + length)} of the array that was split. */
    @FunctionalInterface
    public interface LineConsumer {
        void accept(byte[] bytes, int offset, int length);
    }

    /**
     * Calls {@code action} with the range of {@code bytes} that holds each line. Lines end at {@code \n}, {@code \r}
     * or {@code \r\n}, the terminator is not part of the range, and a trailing terminator does not start another,
     * empty line. These bytes never occur inside a multi-byte UTF-8 sequence, so the split is safe on encoded text.
     * The array is not modified.
     */
    public static void forEach(byte[] bytes, LineConsumer action) {
        int end = bytes.length;
        int lineStart = 0;
        for (int i = 0; i < end; i++) {
            byte b = bytes[i];
            if (b == '\n' || b == '\r') {
                action.accept(bytes, lineStart, i - lineStart);
                if (b == '\r' && i + 1 < end && bytes[i + 1] == '\n') {
                    i++;
                }
                lineStart = i + 1;
            }
        }
        if (lineStart < end) {
            action.accept(bytes, lineStart, end - lineStart);
        }
    }
}
