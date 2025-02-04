/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator.scalar;

import com.google.common.collect.ImmutableList;
import io.airlift.joni.Matcher;
import io.airlift.joni.Option;
import io.airlift.joni.Region;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.Description;
import io.prestosql.spi.function.LiteralParameters;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.SqlNullable;
import io.prestosql.spi.function.SqlType;
import io.prestosql.sql.gen.lambda.UnaryFunctionInterface;
import io.prestosql.type.JoniRegexp;
import io.prestosql.type.JoniRegexpType;

import static io.prestosql.spi.type.VarcharType.VARCHAR;

@ScalarFunction("regexp_replace")
@Description("replaces substrings matching a regular expression using a lambda function")
public final class JoniRegexpReplaceLambdaFunction
{
    private final PageBuilder pageBuilder = new PageBuilder(ImmutableList.of(VARCHAR));

    @LiteralParameters("x")
    @SqlType("varchar")
    @SqlNullable
    public Slice regexpReplace(
            @SqlType("varchar") Slice source,
            @SqlType(JoniRegexpType.NAME) JoniRegexp pattern,
            @SqlType("function(array(varchar), varchar(x))") UnaryFunctionInterface replaceFunction)
    {
        // If there is no match we can simply return the original source without doing copy.
        Matcher matcher = pattern.matcher(source.getBytes());
        if (matcher.search(0, source.length(), Option.DEFAULT) == -1) {
            return source;
        }

        SliceOutput output = new DynamicSliceOutput(source.length());

        // Prepare a BlockBuilder that will be used to create the target block
        // that will be passed to the lambda function.
        if (pageBuilder.isFull()) {
            pageBuilder.reset();
        }
        BlockBuilder blockBuilder = pageBuilder.getBlockBuilder(0);

        int groupCount = pattern.regex().numberOfCaptures();
        int appendPosition = 0;
        int nextStart;

        do {
            // nextStart is the same as the last appendPosition, unless the last match was zero-width.
            // In such case, nextStart is last appendPosition + 1.
            if (matcher.getEnd() == matcher.getBegin()) {
                nextStart = matcher.getEnd() + 1;
            }
            else {
                nextStart = matcher.getEnd();
            }

            // Append the un-matched part
            Slice unmatched = source.slice(appendPosition, matcher.getBegin() - appendPosition);
            appendPosition = matcher.getEnd();
            output.appendBytes(unmatched);

            // Append the capturing groups to the target block that will be passed to lambda
            Region matchedRegion = matcher.getEagerRegion();
            for (int i = 1; i <= groupCount; i++) {
                // Add to the block builder if the matched region is not null. In Joni null is represented as [-1, -1]
                if (matchedRegion.beg[i] >= 0 && matchedRegion.end[i] >= 0) {
                    VARCHAR.writeSlice(blockBuilder, source, matchedRegion.beg[i], matchedRegion.end[i] - matchedRegion.beg[i]);
                }
                else {
                    blockBuilder.appendNull();
                }
            }
            pageBuilder.declarePositions(groupCount);
            Block target = blockBuilder.getRegion(blockBuilder.getPositionCount() - groupCount, groupCount);

            // Call the lambda function to replace the block, and append the result to output
            Slice replaced = (Slice) replaceFunction.apply(target);
            if (replaced == null) {
                // replacing a substring with null (unknown) makes the entire string null
                return null;
            }
            output.appendBytes(replaced);
        }
        while (matcher.search(nextStart, source.length(), Option.DEFAULT) != -1);

        // Append the last un-matched part
        output.writeBytes(source, appendPosition, source.length() - appendPosition);
        return output.slice();
    }
}
