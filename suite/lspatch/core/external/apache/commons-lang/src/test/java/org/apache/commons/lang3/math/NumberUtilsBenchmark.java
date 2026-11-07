/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.lang3.math;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@link NumberUtils#createNumber(String)} See https://github.com/apache/commons-lang/pull/1628
 *
 * <pre>
 * mvn test -P benchmark -Dbenchmark=NumberUtilsBenchmark -P '!jacoco'
 * </pre>
 * Results on my machine on 2026-04-26:
 // @formatter:off
 * <pre>
# JMH version: 1.37
# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11
# VM invoker: /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin/java
# VM options: <none>
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: 3 iterations, 10 s each
# Measurement: 5 iterations, 10 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Average time, time/op
# Benchmark: org.apache.commons.lang3.math.NumberUtilsBenchmark.testDefaultCheck

# Run progress: 0.00% complete, ETA 00:08:00
# Fork: 1 of 3
# Warmup Iteration   1: 33.679 ns/op
# Warmup Iteration   2: 32.796 ns/op
# Warmup Iteration   3: 33.406 ns/op
Iteration   1: 33.474 ns/op
Iteration   2: 33.479 ns/op
Iteration   3: 33.301 ns/op
Iteration   4: 33.691 ns/op
Iteration   5: 33.523 ns/op

# Run progress: 16.67% complete, ETA 00:06:40
# Fork: 2 of 3
# Warmup Iteration   1: 33.716 ns/op
# Warmup Iteration   2: 33.370 ns/op
# Warmup Iteration   3: 33.073 ns/op
Iteration   1: 33.057 ns/op
Iteration   2: 33.076 ns/op
Iteration   3: 32.987 ns/op
Iteration   4: 33.102 ns/op
Iteration   5: 33.060 ns/op

# Run progress: 33.33% complete, ETA 00:05:20
# Fork: 3 of 3
# Warmup Iteration   1: 34.668 ns/op
# Warmup Iteration   2: 33.436 ns/op
# Warmup Iteration   3: 33.182 ns/op
Iteration   1: 33.022 ns/op
Iteration   2: 33.164 ns/op
Iteration   3: 33.171 ns/op
Iteration   4: 33.050 ns/op
Iteration   5: 33.126 ns/op


Result "org.apache.commons.lang3.math.NumberUtilsBenchmark.testDefaultCheck":
  33.219 ±(99.9%) 0.235 ns/op [Average]
  (min, avg, max) = (32.987, 33.219, 33.691), stdev = 0.220
  CI (99.9%): [32.984, 33.454] (assumes normal distribution)


# JMH version: 1.37
# VM version: JDK 21.0.11, OpenJDK 64-Bit Server VM, 21.0.11
# VM invoker: /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home/bin/java
# VM options: <none>
# Blackhole mode: compiler (auto-detected, use -Djmh.blackhole.autoDetect=false to disable)
# Warmup: 3 iterations, 10 s each
# Measurement: 5 iterations, 10 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Average time, time/op
# Benchmark: org.apache.commons.lang3.math.NumberUtilsBenchmark.testShortcircuitCheck

# Run progress: 50.00% complete, ETA 00:04:00
# Fork: 1 of 3
# Warmup Iteration   1: 0.587 ns/op
# Warmup Iteration   2: 0.586 ns/op
# Warmup Iteration   3: 0.586 ns/op
Iteration   1: 0.586 ns/op
Iteration   2: 0.586 ns/op
Iteration   3: 0.586 ns/op
Iteration   4: 0.586 ns/op
Iteration   5: 0.586 ns/op

# Run progress: 66.67% complete, ETA 00:02:40
# Fork: 2 of 3
# Warmup Iteration   1: 0.627 ns/op
# Warmup Iteration   2: 0.586 ns/op
# Warmup Iteration   3: 0.587 ns/op
Iteration   1: 0.586 ns/op
Iteration   2: 0.629 ns/op
Iteration   3: 0.586 ns/op
Iteration   4: 0.585 ns/op
Iteration   5: 0.587 ns/op

# Run progress: 83.33% complete, ETA 00:01:20
# Fork: 3 of 3
# Warmup Iteration   1: 0.628 ns/op
# Warmup Iteration   2: 0.586 ns/op
# Warmup Iteration   3: 0.587 ns/op
Iteration   1: 0.587 ns/op
Iteration   2: 0.587 ns/op
Iteration   3: 0.621 ns/op
Iteration   4: 0.624 ns/op
Iteration   5: 0.587 ns/op


Result "org.apache.commons.lang3.math.NumberUtilsBenchmark.testShortcircuitCheck":
  0.594 ±(99.9%) 0.017 ns/op [Average]
  (min, avg, max) = (0.585, 0.594, 0.629), stdev = 0.016
  CI (99.9%): [0.577, 0.611] (assumes normal distribution)


# Run complete. Total time: 00:08:01

REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on
why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial
experiments, perform baseline and negative tests that provide experimental control, make sure
the benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts.
Do not assume the numbers tell you what you want them to tell.

NOTE: Current JVM experimentally supports Compiler Blackholes, and they are in use. Please exercise
extra caution when trusting the results, look into the generated code to check the benchmark still
works, and factor in a small probability of new VM bugs. Additionally, while comparisons between
different JVMs are already problematic, the performance difference caused by different Blackhole
modes can be very significant. Please make sure you use the consistent Blackhole mode for comparisons.

Benchmark                                   Mode  Cnt   Score   Error  Units
NumberUtilsBenchmark.testDefaultCheck       avgt   15  33.219 ± 0.235  ns/op
NumberUtilsBenchmark.testShortcircuitCheck  avgt   15   0.594 ± 0.017  ns/op

Benchmark result is saved to target/jmh-result.NumberUtilsBenchmark.json
 * </pre>
 // @formatter:on
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class NumberUtilsBenchmark {

    private static boolean isAllZeros(final String str) {
        if (str == null) {
            return true;
        }
        for (int i = str.length() - 1; i >= 0; i--) {
            if (str.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isZero(final String mant, final String dec) {
        return isAllZeros(mant) && isAllZeros(dec);
    }

    private final String str = "0.25";

    private final String mant = "0";

    private final String dec = "25";

    Float f = Float.valueOf(str);

    Double d = Double.valueOf(str);

    @Benchmark
    public boolean testDefaultCheck() {
        return !f.isInfinite() && !(f.floatValue() == 0.0F && !isZero(mant, dec)) && f.toString().equals(d.toString());
    }

    @Benchmark
    public boolean testShortcircuitCheck() {
        return !f.isInfinite() && !(f.floatValue() == 0.0F && !isZero(mant, dec)) && (d.floatValue() == d.doubleValue() || f.toString().equals(d.toString()));
    }
}
