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
package com.facebook.presto.spi.block;

import com.facebook.presto.spi.type.TypeManager;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import io.airlift.slice.SliceOutput;

import static com.facebook.presto.spi.block.EncoderUtil.decodeNullBits;
import static com.facebook.presto.spi.block.EncoderUtil.encodeNullsAsBits;

public class LazySliceArrayBlockEncoding
        implements BlockEncoding
{
    public static final BlockEncodingFactory<LazySliceArrayBlockEncoding> FACTORY = new LazySliceArrayBlockEncodingFactory();
    private static final String NAME = "LAZY_SLICE_ARRAY";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void writeBlock(SliceOutput sliceOutput, Block block)
    {
        // The down casts here are safe because it is the block itself the provides this encoding implementation.
        LazySliceArrayBlock sliceArrayBlock = (LazySliceArrayBlock) block;

        int positionCount = sliceArrayBlock.getPositionCount();
        sliceOutput.appendInt(positionCount);

        // offsets
        for (int position = 0; position < positionCount; position++) {
            int length = 0;
            if (!sliceArrayBlock.isNull(position)) {
                length = sliceArrayBlock.getLength(position);
            }
            sliceOutput.appendInt(length);
        }

        encodeNullsAsBits(sliceOutput, sliceArrayBlock);

        // write last null bits
        if ((positionCount & 0b111) > 0) {
            byte value = 0;
            int mask = 0b1000_0000;
            for (int position = positionCount & ~0b111; position < positionCount; position++) {
                value |= sliceArrayBlock.isNull(position) ? mask : 0;
                mask >>>= 1;
            }
            sliceOutput.appendByte(value);
        }

        for (Slice value : sliceArrayBlock.getValues()) {
            if (value != null) {
                sliceOutput.writeBytes(value);
            }
        }
    }

    @Override
    public Block readBlock(SliceInput sliceInput)
    {
        int positionCount = sliceInput.readInt();

        // offsets
        int[] offsets = new int[positionCount + 1];
        int offset = 0;
        for (int position = 0; position < positionCount; position++) {
            offset += sliceInput.readInt();
            offsets[position + 1] = offset;
        }

        boolean[] valueIsNull = decodeNullBits(sliceInput, positionCount);

        Slice[] values = new Slice[positionCount];
        for (int position = 0; position < positionCount; position++) {
            if (!valueIsNull[position]) {
                values[position] = sliceInput.readSlice(offsets[position + 1] - offsets[position]);
            }
        }

        return new SliceArrayBlock(positionCount, values);
    }

    public static class LazySliceArrayBlockEncodingFactory
            implements BlockEncodingFactory<LazySliceArrayBlockEncoding>
    {
        @Override
        public String getName()
        {
            return NAME;
        }

        @Override
        public LazySliceArrayBlockEncoding readEncoding(TypeManager manager, BlockEncodingSerde serde, SliceInput input)
        {
            return new LazySliceArrayBlockEncoding();
        }

        @Override
        public void writeEncoding(BlockEncodingSerde serde, SliceOutput output, LazySliceArrayBlockEncoding blockEncoding)
        {
        }
    }
}
