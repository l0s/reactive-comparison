/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package integrationtest;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

public class RandomStringGenerator {

    private static final char[] symbols;
    private final Random random;

    static {
        final var builder = new StringBuilder();
        for( char i = '0'; i <= '9'; builder.append( i++ ) );
        for( char i = 'A'; i <= 'Z'; builder.append( i++ ) );
        for( char i = 'a'; i <= 'z'; builder.append( i++ ) );
        symbols = builder.toString().toCharArray();
    }

    public RandomStringGenerator(final Random random) {
        Objects.requireNonNull(random);
        this.random = random;
    }

    public RandomStringGenerator() {
        this(new SecureRandom());
    }

    public String generateString(final int length) {
        final var symbols = getSymbols();
        final var builder = new StringBuilder();
        getRandom().ints(length, 0, symbols.length).mapToObj(index -> symbols[index]).forEachOrdered(builder::append);
        return builder.toString();
    }

    protected char[] getSymbols() {
        return symbols;
    }

    protected Random getRandom() {
        return random;
    }

}