/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.orc.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Map;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.Decimal64ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.expressions.StringExpr;
import org.apache.hadoop.hive.ql.util.TimestampUtils;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.orc.OrcProto;
import org.apache.orc.TypeDescription;
import org.apache.orc.TypeDescription.Category;

/**
 * Convert ORC tree readers.
 */
public class ConvertTreeReaderFactory extends TreeReaderFactory {

  /**
   * Override methods like checkEncoding to pass-thru to the convert TreeReader.
   */
  public static class ConvertTreeReader extends TreeReader {

    TreeReader fromReader;

    ConvertTreeReader(int columnId, TreeReader fromReader) throws IOException {
      super(columnId, null);
      this.fromReader = fromReader;
    }

    // The ordering of types here is used to determine which numeric types
    // are common/convertible to one another. Probably better to rely on the
    // ordering explicitly defined here than to assume that the enum values
    // that were arbitrarily assigned in PrimitiveCategory work for our purposes.
    private static EnumMap<TypeDescription.Category, Integer> numericTypes =
        new EnumMap<>(TypeDescription.Category.class);

    static {
      registerNumericType(TypeDescription.Category.BOOLEAN, 1);
      registerNumericType(TypeDescription.Category.BYTE, 2);
      registerNumericType(TypeDescription.Category.SHORT, 3);
      registerNumericType(TypeDescription.Category.INT, 4);
      registerNumericType(TypeDescription.Category.LONG, 5);
      registerNumericType(TypeDescription.Category.FLOAT, 6);
      registerNumericType(TypeDescription.Category.DOUBLE, 7);
      registerNumericType(TypeDescription.Category.DECIMAL, 8);
    }

    private static void registerNumericType(TypeDescription.Category kind, int level) {
      numericTypes.put(kind, level);
    }

    static TreeReader getStringGroupTreeReader(int columnId,
        TypeDescription fileType, Context context) throws IOException {
      switch (fileType.getCategory()) {
      case STRING:
        return new StringTreeReader(columnId, context);
      case CHAR:
        return new CharTreeReader(columnId, fileType.getMaxLength());
      case VARCHAR:
        return new VarcharTreeReader(columnId, fileType.getMaxLength());
      default:
        throw new RuntimeException("Unexpected type kind " + fileType.getCategory().name());
      }
    }

    protected void assignStringGroupVectorEntry(BytesColumnVector bytesColVector,
        int elementNum, TypeDescription readerType, byte[] bytes) {
      assignStringGroupVectorEntry(bytesColVector,
          elementNum, readerType, bytes, 0, bytes.length);
    }

    /*
     * Assign a BytesColumnVector entry when we have a byte array, start, and
     * length for the string group which can be (STRING, CHAR, VARCHAR).
     */
    protected void assignStringGroupVectorEntry(BytesColumnVector bytesColVector,
        int elementNum, TypeDescription readerType, byte[] bytes, int start, int length) {
      switch (readerType.getCategory()) {
      case STRING:
        bytesColVector.setVal(elementNum, bytes, start, length);
        break;
      case CHAR:
        {
          int adjustedDownLen =
              StringExpr.rightTrimAndTruncate(bytes, start, length, readerType.getMaxLength());
          bytesColVector.setVal(elementNum, bytes, start, adjustedDownLen);
        }
        break;
      case VARCHAR:
        {
          int adjustedDownLen =
              StringExpr.truncate(bytes, start, length, readerType.getMaxLength());
          bytesColVector.setVal(elementNum, bytes, start, adjustedDownLen);
        }
        break;
      default:
        throw new RuntimeException("Unexpected type kind " + readerType.getCategory().name());
      }
    }

    protected void convertStringGroupVectorElement(BytesColumnVector bytesColVector,
        int elementNum, TypeDescription readerType) {
      switch (readerType.getCategory()) {
      case STRING:
        // No conversion needed.
        break;
      case CHAR:
        {
          int length = bytesColVector.length[elementNum];
          int adjustedDownLen = StringExpr
            .rightTrimAndTruncate(bytesColVector.vector[elementNum],
                bytesColVector.start[elementNum], length,
                readerType.getMaxLength());
          if (adjustedDownLen < length) {
            bytesColVector.length[elementNum] = adjustedDownLen;
          }
        }
        break;
      case VARCHAR:
        {
          int length = bytesColVector.length[elementNum];
          int adjustedDownLen = StringExpr
            .truncate(bytesColVector.vector[elementNum],
                bytesColVector.start[elementNum], length,
                readerType.getMaxLength());
          if (adjustedDownLen < length) {
            bytesColVector.length[elementNum] = adjustedDownLen;
          }
        }
        break;
      default:
        throw new RuntimeException("Unexpected type kind " + readerType.getCategory().name());
      }
    }

    private boolean isParseError;

    /*
     * We do this because we want the various parse methods return a primitive.
     *
     * @return true if there was a parse error in the last call to
     * parseLongFromString, etc.
     */
    protected boolean getIsParseError() {
      return isParseError;
    }

    protected long parseLongFromString(String string) {
      try {
        long longValue = Long.parseLong(string);
        isParseError = false;
        return longValue;
      } catch (NumberFormatException e) {
        isParseError = true;
        return 0;
      }
    }

    protected float parseFloatFromString(String string) {
      try {
        float floatValue = Float.parseFloat(string);
        isParseError = false;
        return floatValue;
      } catch (NumberFormatException e) {
        isParseError = true;
        return Float.NaN;
      }
    }

    protected double parseDoubleFromString(String string) {
      try {
        double value = Double.parseDouble(string);
        isParseError = false;
        return value;
      } catch (NumberFormatException e) {
        isParseError = true;
        return Double.NaN;
      }
    }

    /**
     * @param string
     * @return the HiveDecimal parsed, or null if there was a parse error.
     */
    protected HiveDecimal parseDecimalFromString(String string) {
      try {
        HiveDecimal value = HiveDecimal.create(string);
        return value;
      } catch (NumberFormatException e) {
        return null;
      }
    }

    /**
     * @param string
     * @return the Timestamp parsed, or null if there was a parse error.
     */
    protected Timestamp parseTimestampFromString(String string) {
      try {
        Timestamp value = Timestamp.valueOf(string);
        return value;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    /**
     * @param string
     * @return the Date parsed, or null if there was a parse error.
     */
    protected Date parseDateFromString(String string) {
      try {
        Date value = Date.valueOf(string);
        return value;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    protected String stringFromBytesColumnVectorEntry(
        BytesColumnVector bytesColVector, int elementNum) {
      String string;

      string = new String(
          bytesColVector.vector[elementNum],
          bytesColVector.start[elementNum], bytesColVector.length[elementNum],
          StandardCharsets.UTF_8);

      return string;
    }

    private static final double MIN_LONG_AS_DOUBLE = -0x1p63;
    /*
     * We cannot store Long.MAX_VALUE as a double without losing precision. Instead, we store
     * Long.MAX_VALUE + 1 == -Long.MIN_VALUE, and then offset all comparisons by 1.
     */
    private static final double MAX_LONG_AS_DOUBLE_PLUS_ONE = 0x1p63;

    public boolean doubleCanFitInLong(double doubleValue) {

      // Borrowed from Guava DoubleMath.roundToLong except do not want dependency on Guava and we
      // don't want to catch an exception.

      return ((MIN_LONG_AS_DOUBLE - doubleValue < 1.0) &&
              (doubleValue < MAX_LONG_AS_DOUBLE_PLUS_ONE));
    }

    @Override
    void checkEncoding(OrcProto.ColumnEncoding encoding) throws IOException {
      // Pass-thru.
      fromReader.checkEncoding(encoding);
    }

    @Override
    void startStripe(Map<StreamName, InStream> streams,
        OrcProto.StripeFooter stripeFooter
    ) throws IOException {
      // Pass-thru.
      fromReader.startStripe(streams, stripeFooter);
    }

    @Override
    public void seek(PositionProvider[] index) throws IOException {
     // Pass-thru.
      fromReader.seek(index);
    }

    @Override
    public void seek(PositionProvider index) throws IOException {
      // Pass-thru.
      fromReader.seek(index);
    }

    @Override
    void skipRows(long items) throws IOException {
      // Pass-thru.
      fromReader.skipRows(items);
    }

    /**
     * Override this to use convertVector.
     * Source and result are member variables in the subclass with the right
     * type.
     * @param elementNum
     * @throws IOException
     */
    // Override this to use convertVector.
    public void setConvertVectorElement(int elementNum) throws IOException {
      throw new RuntimeException("Expected this method to be overriden");
    }

    // Common code used by the conversion.
    public void convertVector(ColumnVector fromColVector,
        ColumnVector resultColVector, final int batchSize) throws IOException {

      resultColVector.reset();
      if (fromColVector.isRepeating) {
        resultColVector.isRepeating = true;
        if (fromColVector.noNulls || !fromColVector.isNull[0]) {
          setConvertVectorElement(0);
        } else {
          resultColVector.noNulls = false;
          resultColVector.isNull[0] = true;
        }
      } else if (fromColVector.noNulls){
        for (int i = 0; i < batchSize; i++) {
          setConvertVectorElement(i);
        }
      } else {
        for (int i = 0; i < batchSize; i++) {
          if (!fromColVector.isNull[i]) {
            setConvertVectorElement(i);
          } else {
            resultColVector.noNulls = false;
            resultColVector.isNull[i] = true;
          }
        }
      }
    }

    public void downCastAnyInteger(LongColumnVector longColVector, int elementNum,
        TypeDescription readerType) {
      downCastAnyInteger(longColVector, elementNum, longColVector.vector[elementNum], readerType);
    }

    public void downCastAnyInteger(LongColumnVector longColVector, int elementNum, long inputLong,
        TypeDescription readerType) {
      long[] vector = longColVector.vector;
      long outputLong;
      Category readerCategory = readerType.getCategory();
      switch (readerCategory) {
      case BOOLEAN:
        // No data loss for boolean.
        vector[elementNum] = inputLong == 0 ? 0 : 1;
        return;
      case BYTE:
        outputLong = (byte) inputLong;
        break;
      case SHORT:
        outputLong = (short) inputLong;
        break;
      case INT:
        outputLong = (int) inputLong;
        break;
      case LONG:
        // No data loss for long.
        vector[elementNum] = inputLong;
        return;
      default:
        throw new RuntimeException("Unexpected type kind " + readerCategory.name());
      }

      if (outputLong != inputLong) {
        // Data loss.
        longColVector.isNull[elementNum] = true;
        longColVector.noNulls = false;
      } else {
        vector[elementNum] = outputLong;
      }
    }

    protected boolean integerDownCastNeeded(TypeDescription fileType, TypeDescription readerType) {
      Integer fileLevel = numericTypes.get(fileType.getCategory());
      Integer schemaLevel = numericTypes.get(readerType.getCategory());
      return (schemaLevel.intValue() < fileLevel.intValue());
    }
  }

  private static TreeReader createFromInteger(int columnId,
                                              TypeDescription fileType,
                                              Context context) throws IOException {
    switch (fileType.getCategory()) {
    case BOOLEAN:
      return new BooleanTreeReader(columnId);
    case BYTE:
      return new ByteTreeReader(columnId);
    case SHORT:
      return new ShortTreeReader(columnId, context);
    case INT:
      return new IntTreeReader(columnId, context);
    case LONG:
      return new LongTreeReader(columnId, context);
    default:
      throw new RuntimeException("Unexpected type kind " + fileType);
    }
  }

  public static class AnyIntegerFromAnyIntegerTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private final boolean downCastNeeded;

    AnyIntegerFromAnyIntegerTreeReader(int columnId, TypeDescription fileType, TypeDescription readerType,
      Context context) throws IOException {
      super(columnId, createFromInteger(columnId, fileType, context));
      this.readerType = readerType;
      downCastNeeded = integerDownCastNeeded(fileType, readerType);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      fromReader.nextVector(previousVector, isNull, batchSize);
      LongColumnVector resultColVector = (LongColumnVector) previousVector;
      if (downCastNeeded) {
        if (resultColVector.isRepeating) {
          if (resultColVector.noNulls || !resultColVector.isNull[0]) {
            downCastAnyInteger(resultColVector, 0, readerType);
          }
        } else if (resultColVector.noNulls){
          for (int i = 0; i < batchSize; i++) {
            downCastAnyInteger(resultColVector, i, readerType);
          }
        } else {
          for (int i = 0; i < batchSize; i++) {
            if (!resultColVector.isNull[i]) {
              downCastAnyInteger(resultColVector, i, readerType);
            }
          }
        }
      }
    }
  }

  public static class AnyIntegerFromDoubleTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private DoubleColumnVector doubleColVector;
    private LongColumnVector longColVector;

    AnyIntegerFromDoubleTreeReader(int columnId, TypeDescription fileType,
                                   TypeDescription readerType)
        throws IOException {
      super(columnId, fileType.getCategory() == Category.DOUBLE ?
                          new DoubleTreeReader(columnId) :
                          new FloatTreeReader(columnId));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      double doubleValue = doubleColVector.vector[elementNum];
      if (!doubleCanFitInLong(doubleValue)) {
        longColVector.isNull[elementNum] = true;
        longColVector.noNulls = false;
      } else {
        downCastAnyInteger(longColVector, elementNum, (long) doubleValue, readerType);
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (doubleColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        doubleColVector = new DoubleColumnVector();
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(doubleColVector, isNull, batchSize);

      convertVector(doubleColVector, longColVector, batchSize);
    }
  }

  public static class AnyIntegerFromDecimalTreeReader extends ConvertTreeReader {
    private final int precision;
    private final int scale;
    private final TypeDescription readerType;
    private DecimalColumnVector decimalColVector;
    private LongColumnVector longColVector;

    AnyIntegerFromDecimalTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, new DecimalTreeReader(columnId, fileType.getPrecision(),
          fileType.getScale(), context));
      this.precision = fileType.getPrecision();
      this.scale = fileType.getScale();
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      HiveDecimalWritable decWritable = decimalColVector.vector[elementNum];
      long[] vector = longColVector.vector;
      Category readerCategory = readerType.getCategory();

      // Check to see if the decimal will fit in the Hive integer data type.
      // If not, set the element to null.
      boolean isInRange;
      switch (readerCategory) {
        case BOOLEAN:
          // No data loss for boolean.
          vector[elementNum] = decWritable.signum() == 0 ? 0 : 1;
          return;
        case BYTE:
          isInRange = decWritable.isByte();
          break;
        case SHORT:
          isInRange = decWritable.isShort();
          break;
        case INT:
          isInRange = decWritable.isInt();
          break;
        case LONG:
          isInRange = decWritable.isLong();
          break;
        default:
          throw new RuntimeException("Unexpected type kind " + readerCategory.name());
      }
      if (!isInRange) {
        longColVector.isNull[elementNum] = true;
        longColVector.noNulls = false;
      } else {
        switch (readerCategory) {
          case BYTE:
            vector[elementNum] = decWritable.byteValue();
            break;
          case SHORT:
            vector[elementNum] = decWritable.shortValue();
            break;
          case INT:
            vector[elementNum] = decWritable.intValue();
            break;
          case LONG:
            vector[elementNum] = decWritable.longValue();
            break;
          default:
            throw new RuntimeException("Unexpected type kind " + readerCategory.name());
        }
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (decimalColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        decimalColVector = new DecimalColumnVector(precision, scale);
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(decimalColVector, isNull, batchSize);

      convertVector(decimalColVector, longColVector, batchSize);
    }
  }

  public static class AnyIntegerFromStringGroupTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private BytesColumnVector bytesColVector;
    private LongColumnVector longColVector;

    AnyIntegerFromStringGroupTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      String string = stringFromBytesColumnVectorEntry(bytesColVector, elementNum);
      long longValue = parseLongFromString(string);
      if (!getIsParseError()) {
        downCastAnyInteger(longColVector, elementNum, longValue, readerType);
      } else {
        longColVector.noNulls = false;
        longColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (bytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        bytesColVector = new BytesColumnVector();
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(bytesColVector, isNull, batchSize);

      convertVector(bytesColVector, longColVector, batchSize);
    }
  }

  public static class AnyIntegerFromTimestampTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private TimestampColumnVector timestampColVector;
    private LongColumnVector longColVector;

    AnyIntegerFromTimestampTreeReader(int columnId, TypeDescription readerType,
        Context context) throws IOException {
      super(columnId, new TimestampTreeReader(columnId, context));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      // Use TimestampWritable's getSeconds.
      long longValue = TimestampUtils.millisToSeconds(
          timestampColVector.asScratchTimestamp(elementNum).getTime());
      downCastAnyInteger(longColVector, elementNum, longValue, readerType);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (timestampColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        timestampColVector = new TimestampColumnVector();
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(timestampColVector, isNull, batchSize);

      convertVector(timestampColVector, longColVector, batchSize);
    }
  }

  public static class DoubleFromAnyIntegerTreeReader extends ConvertTreeReader {
    private LongColumnVector longColVector;
    private DoubleColumnVector doubleColVector;

    DoubleFromAnyIntegerTreeReader(int columnId, TypeDescription fileType,
        Context context) throws IOException {
      super(columnId, createFromInteger(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {

      double doubleValue = (double) longColVector.vector[elementNum];
      if (!Double.isNaN(doubleValue)) {
        doubleColVector.vector[elementNum] = doubleValue;
      } else {
        doubleColVector.vector[elementNum] = Double.NaN;
        doubleColVector.noNulls = false;
        doubleColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        doubleColVector = (DoubleColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, doubleColVector, batchSize);
    }
  }

  public static class DoubleFromDecimalTreeReader extends ConvertTreeReader {
    private final int precision;
    private final int scale;
    private DecimalColumnVector decimalColVector;
    private DoubleColumnVector doubleColVector;

    DoubleFromDecimalTreeReader(int columnId, TypeDescription fileType, Context context) throws IOException {
      super(columnId, new DecimalTreeReader(columnId, fileType.getPrecision(),
          fileType.getScale(), context));
      this.precision = fileType.getPrecision();
      this.scale = fileType.getScale();
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      doubleColVector.vector[elementNum] =
          decimalColVector.vector[elementNum].doubleValue();
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (decimalColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        decimalColVector = new DecimalColumnVector(precision, scale);
        doubleColVector = (DoubleColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(decimalColVector, isNull, batchSize);

      convertVector(decimalColVector, doubleColVector, batchSize);
    }
  }

  public static class DoubleFromStringGroupTreeReader extends ConvertTreeReader {
    private BytesColumnVector bytesColVector;
    private DoubleColumnVector doubleColVector;

    DoubleFromStringGroupTreeReader(int columnId, TypeDescription fileType, Context context)
        throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      String string = stringFromBytesColumnVectorEntry(bytesColVector, elementNum);
      double doubleValue = parseDoubleFromString(string);
      if (!getIsParseError()) {
        doubleColVector.vector[elementNum] = doubleValue;
      } else {
        doubleColVector.noNulls = false;
        doubleColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (bytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        bytesColVector = new BytesColumnVector();
        doubleColVector = (DoubleColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(bytesColVector, isNull, batchSize);

      convertVector(bytesColVector, doubleColVector, batchSize);
    }
  }

  public static class DoubleFromTimestampTreeReader extends ConvertTreeReader {
    private TimestampColumnVector timestampColVector;
    private DoubleColumnVector doubleColVector;

    DoubleFromTimestampTreeReader(int columnId, Context context) throws IOException {
      super(columnId, new TimestampTreeReader(columnId, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      doubleColVector.vector[elementNum] = TimestampUtils.getDouble(
          timestampColVector.asScratchTimestamp(elementNum));
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (timestampColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        timestampColVector = new TimestampColumnVector();
        doubleColVector = (DoubleColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(timestampColVector, isNull, batchSize);

      convertVector(timestampColVector, doubleColVector, batchSize);
    }
  }

  public static class FloatFromDoubleTreeReader extends ConvertTreeReader {
    FloatFromDoubleTreeReader(int columnId, Context context) throws IOException {
      super(columnId, new DoubleTreeReader(columnId));
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      // Read present/isNull stream
      fromReader.nextVector(previousVector, isNull, batchSize);
      DoubleColumnVector vector = (DoubleColumnVector) previousVector;
      if (previousVector.isRepeating) {
        vector.vector[0] = (float) vector.vector[0];
      } else {
        for(int i=0; i < batchSize; ++i) {
          vector.vector[i] = (float) vector.vector[i];
        }
      }
    }
  }

  public static class DecimalFromAnyIntegerTreeReader extends ConvertTreeReader {
    private LongColumnVector longColVector;
    private ColumnVector decimalColVector;

    DecimalFromAnyIntegerTreeReader(int columnId, TypeDescription fileType, Context context)
        throws IOException {
      super(columnId, createFromInteger(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      long longValue = longColVector.vector[elementNum];
      HiveDecimalWritable hiveDecimalWritable = new HiveDecimalWritable(longValue);
      // The DecimalColumnVector will enforce precision and scale and set the entry to null when out of bounds.
      if (decimalColVector instanceof Decimal64ColumnVector) {
        ((Decimal64ColumnVector) decimalColVector).set(elementNum, hiveDecimalWritable);
      } else {
        ((DecimalColumnVector) decimalColVector).set(elementNum, hiveDecimalWritable);
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
        boolean[] isNull,
        final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        decimalColVector = previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, decimalColVector, batchSize);
    }
  }

  public static class DecimalFromDoubleTreeReader extends ConvertTreeReader {
    private DoubleColumnVector doubleColVector;
    private ColumnVector decimalColVector;

    DecimalFromDoubleTreeReader(int columnId, TypeDescription fileType,
                                TypeDescription readerType)
        throws IOException {
      super(columnId, fileType.getCategory() == Category.DOUBLE ?
                          new DoubleTreeReader(columnId) :
                          new FloatTreeReader(columnId));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      HiveDecimal value =
          HiveDecimal.create(Double.toString(doubleColVector.vector[elementNum]));
      if (value != null) {
        if (decimalColVector instanceof Decimal64ColumnVector) {
          ((Decimal64ColumnVector) decimalColVector).set(elementNum, value);
        } else {
          ((DecimalColumnVector) decimalColVector).set(elementNum, value);
        }
      } else {
        decimalColVector.noNulls = false;
        decimalColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (doubleColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        doubleColVector = new DoubleColumnVector();
        decimalColVector = previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(doubleColVector, isNull, batchSize);

      convertVector(doubleColVector, decimalColVector, batchSize);
    }
  }

  public static class DecimalFromStringGroupTreeReader extends ConvertTreeReader {
    private BytesColumnVector bytesColVector;
    private ColumnVector decimalColVector;

    DecimalFromStringGroupTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      String string = stringFromBytesColumnVectorEntry(bytesColVector, elementNum);
      HiveDecimal value = parseDecimalFromString(string);
      if (value != null) {
        // The DecimalColumnVector will enforce precision and scale and set the entry to null when out of bounds.
        if (decimalColVector instanceof Decimal64ColumnVector) {
          ((Decimal64ColumnVector) decimalColVector).set(elementNum, value);
        } else {
          ((DecimalColumnVector) decimalColVector).set(elementNum, value);
        }
      } else {
        decimalColVector.noNulls = false;
        decimalColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (bytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        bytesColVector = new BytesColumnVector();
        decimalColVector = previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(bytesColVector, isNull, batchSize);

      convertVector(bytesColVector, decimalColVector, batchSize);
    }
  }

  public static class DecimalFromTimestampTreeReader extends ConvertTreeReader {
    private TimestampColumnVector timestampColVector;
    private ColumnVector decimalColVector;

    DecimalFromTimestampTreeReader(int columnId, Context context) throws IOException {
      super(columnId, new TimestampTreeReader(columnId, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      double doubleValue = TimestampUtils.getDouble(
          timestampColVector.asScratchTimestamp(elementNum));
      HiveDecimal value = HiveDecimal.create(Double.toString(doubleValue));
      if (value != null) {
        // The DecimalColumnVector will enforce precision and scale and set the entry to null when out of bounds.
        if (decimalColVector instanceof Decimal64ColumnVector) {
          ((Decimal64ColumnVector) decimalColVector).set(elementNum, value);
        } else {
          ((DecimalColumnVector) decimalColVector).set(elementNum, value);
        }
      } else {
        decimalColVector.noNulls = false;
        decimalColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (timestampColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        timestampColVector = new TimestampColumnVector();
        decimalColVector = previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(timestampColVector, isNull, batchSize);

      convertVector(timestampColVector, decimalColVector, batchSize);
    }
  }

  public static class DecimalFromDecimalTreeReader extends ConvertTreeReader {
    private DecimalColumnVector fileDecimalColVector;
    private int filePrecision;
    private int fileScale;
    private ColumnVector decimalColVector;

    DecimalFromDecimalTreeReader(int columnId, TypeDescription fileType, TypeDescription readerType, Context context)
        throws IOException {
      super(columnId, new DecimalTreeReader(columnId, fileType.getPrecision(),
          fileType.getScale(), context));
      filePrecision = fileType.getPrecision();
      fileScale = fileType.getScale();
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {

      if (decimalColVector instanceof Decimal64ColumnVector) {
        ((Decimal64ColumnVector) decimalColVector).set(elementNum, fileDecimalColVector.vector[elementNum]);
      } else {
        ((DecimalColumnVector) decimalColVector).set(elementNum, fileDecimalColVector.vector[elementNum]);
      }

    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (fileDecimalColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        fileDecimalColVector = new DecimalColumnVector(filePrecision, fileScale);
        decimalColVector = previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(fileDecimalColVector, isNull, batchSize);

      convertVector(fileDecimalColVector, decimalColVector, batchSize);
    }
  }

  public static class StringGroupFromAnyIntegerTreeReader extends ConvertTreeReader {
    protected final TypeDescription readerType;
    protected LongColumnVector longColVector;
    protected BytesColumnVector bytesColVector;

    StringGroupFromAnyIntegerTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, createFromInteger(columnId, fileType, context));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      byte[] bytes = Long.toString(longColVector.vector[elementNum])
                         .getBytes(StandardCharsets.UTF_8);
      assignStringGroupVectorEntry(bytesColVector, elementNum, readerType, bytes);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        bytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, bytesColVector, batchSize);
    }
  }

  public static class StringGroupFromBooleanTreeReader extends StringGroupFromAnyIntegerTreeReader {

    StringGroupFromBooleanTreeReader(int columnId, TypeDescription fileType,
                                     TypeDescription readerType,
                                     Context context) throws IOException {
      super(columnId, fileType, readerType, context);
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      byte[] bytes = (longColVector.vector[elementNum] != 0 ? "TRUE" : "FALSE")
         .getBytes(StandardCharsets.UTF_8);
      assignStringGroupVectorEntry(bytesColVector, elementNum, readerType, bytes);
    }
  }

  public static class StringGroupFromDoubleTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private DoubleColumnVector doubleColVector;
    private BytesColumnVector bytesColVector;

    StringGroupFromDoubleTreeReader(int columnId, TypeDescription fileType,
                                    TypeDescription readerType,
        Context context) throws IOException {
      super(columnId, fileType.getCategory() == Category.DOUBLE ?
                          new DoubleTreeReader(columnId) :
                          new FloatTreeReader(columnId));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      double doubleValue = doubleColVector.vector[elementNum];
      if (!Double.isNaN(doubleValue)) {
        String string = String.valueOf(doubleValue);
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        assignStringGroupVectorEntry(bytesColVector, elementNum, readerType, bytes);
      } else {
        bytesColVector.noNulls = false;
        bytesColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (doubleColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        doubleColVector = new DoubleColumnVector();
        bytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(doubleColVector, isNull, batchSize);

      convertVector(doubleColVector, bytesColVector, batchSize);
    }
  }



  public static class StringGroupFromDecimalTreeReader extends ConvertTreeReader {
    private int precision;
    private int scale;
    private final TypeDescription readerType;
    private DecimalColumnVector decimalColVector;
    private BytesColumnVector bytesColVector;
    private byte[] scratchBuffer;

    StringGroupFromDecimalTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId,  new DecimalTreeReader(columnId, fileType.getPrecision(),
          fileType.getScale(), context));
      this.precision = fileType.getPrecision();
      this.scale = fileType.getScale();
      this.readerType = readerType;
      scratchBuffer = new byte[HiveDecimal.SCRATCH_BUFFER_LEN_TO_BYTES];
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      HiveDecimalWritable decWritable = decimalColVector.vector[elementNum];

      // Convert decimal into bytes instead of a String for better performance.
      final int byteIndex = decWritable.toBytes(scratchBuffer);

      assignStringGroupVectorEntry(
          bytesColVector, elementNum, readerType,
          scratchBuffer, byteIndex, HiveDecimal.SCRATCH_BUFFER_LEN_TO_BYTES - byteIndex);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (decimalColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        decimalColVector = new DecimalColumnVector(precision, scale);
        bytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(decimalColVector, isNull, batchSize);

      convertVector(decimalColVector, bytesColVector, batchSize);
    }
  }

  public static class StringGroupFromTimestampTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private TimestampColumnVector timestampColVector;
    private BytesColumnVector bytesColVector;

    StringGroupFromTimestampTreeReader(int columnId, TypeDescription readerType,
        Context context) throws IOException {
      super(columnId, new TimestampTreeReader(columnId, context));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      String string =
          timestampColVector.asScratchTimestamp(elementNum).toString();
      byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
      assignStringGroupVectorEntry(bytesColVector, elementNum, readerType, bytes);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (timestampColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        timestampColVector = new TimestampColumnVector();
        bytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(timestampColVector, isNull, batchSize);

      convertVector(timestampColVector, bytesColVector, batchSize);
    }
  }

  public static class StringGroupFromDateTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private LongColumnVector longColVector;
    private BytesColumnVector bytesColVector;
    private Date date;

    StringGroupFromDateTreeReader(int columnId, TypeDescription readerType,
        Context context) throws IOException {
      super(columnId, new DateTreeReader(columnId, context));
      this.readerType = readerType;
      date = new Date(0);
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      date.setTime(DateWritable.daysToMillis((int) longColVector.vector[elementNum]));
      String string = date.toString();
      byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
      assignStringGroupVectorEntry(bytesColVector, elementNum, readerType, bytes);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        bytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, bytesColVector, batchSize);
    }
  }

  public static class StringGroupFromStringGroupTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;

    StringGroupFromStringGroupTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
      this.readerType = readerType;
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      fromReader.nextVector(previousVector, isNull, batchSize);

      BytesColumnVector resultColVector = (BytesColumnVector) previousVector;

      if (resultColVector.isRepeating) {
        if (resultColVector.noNulls || !resultColVector.isNull[0]) {
          convertStringGroupVectorElement(resultColVector, 0, readerType);
        } else {
          // Remains null.
        }
      } else if (resultColVector.noNulls){
        for (int i = 0; i < batchSize; i++) {
          convertStringGroupVectorElement(resultColVector, i, readerType);
        }
      } else {
        for (int i = 0; i < batchSize; i++) {
          if (!resultColVector.isNull[i]) {
            convertStringGroupVectorElement(resultColVector, i, readerType);
          } else {
            // Remains null.
          }
        }
      }
    }
  }

  public static class StringGroupFromBinaryTreeReader extends ConvertTreeReader {
    private final TypeDescription readerType;
    private BytesColumnVector inBytesColVector;
    private BytesColumnVector outBytesColVector;

    StringGroupFromBinaryTreeReader(int columnId, TypeDescription readerType,
        Context context) throws IOException {
      super(columnId, new BinaryTreeReader(columnId, context));
      this.readerType = readerType;
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      byte[] bytes = inBytesColVector.vector[elementNum];
      int start = inBytesColVector.start[elementNum];
      int length = inBytesColVector.length[elementNum];
      byte[] string = new byte[length == 0 ? 0 : 3 * length - 1];
      for(int p = 0; p < string.length; p += 2) {
        if (p != 0) {
          string[p++] = ' ';
        }
        int num = 0xff & bytes[start++];
        int digit = num / 16;
        string[p] = (byte)((digit) + (digit < 10 ? '0' : 'a' - 10));
        digit = num % 16;
        string[p + 1] = (byte)((digit) + (digit < 10 ? '0' : 'a' - 10));
      }
      assignStringGroupVectorEntry(outBytesColVector, elementNum, readerType,
          string, 0, string.length);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (inBytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        inBytesColVector = new BytesColumnVector();
        outBytesColVector = (BytesColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(inBytesColVector, isNull, batchSize);

      convertVector(inBytesColVector, outBytesColVector, batchSize);
    }
  }

  public static class TimestampFromAnyIntegerTreeReader extends ConvertTreeReader {
    private LongColumnVector longColVector;
    private TimestampColumnVector timestampColVector;

    TimestampFromAnyIntegerTreeReader(int columnId, TypeDescription fileType,
        Context context) throws IOException {
      super(columnId, createFromInteger(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      long longValue = longColVector.vector[elementNum];
      // UNDONE: What does the boolean setting need to be?
      timestampColVector.set(elementNum, new Timestamp(longValue));
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        timestampColVector = (TimestampColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, timestampColVector, batchSize);
    }
  }

  public static class TimestampFromDoubleTreeReader extends ConvertTreeReader {
    private DoubleColumnVector doubleColVector;
    private TimestampColumnVector timestampColVector;

    TimestampFromDoubleTreeReader(int columnId, TypeDescription fileType,
        TypeDescription readerType, Context context) throws IOException {
      super(columnId, fileType.getCategory() == Category.DOUBLE ?
                          new DoubleTreeReader(columnId) :
                          new FloatTreeReader(columnId));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      double doubleValue = doubleColVector.vector[elementNum];
      Timestamp timestampValue = TimestampUtils.doubleToTimestamp(doubleValue);
      // The TimestampColumnVector will set the entry to null when a null timestamp is passed in.
      timestampColVector.set(elementNum, timestampValue);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (doubleColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        doubleColVector = new DoubleColumnVector();
        timestampColVector = (TimestampColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(doubleColVector, isNull, batchSize);

      convertVector(doubleColVector, timestampColVector, batchSize);
    }
  }

  public static class TimestampFromDecimalTreeReader extends ConvertTreeReader {
    private final int precision;
    private final int scale;
    private DecimalColumnVector decimalColVector;
    private TimestampColumnVector timestampColVector;

    TimestampFromDecimalTreeReader(int columnId, TypeDescription fileType,
        Context context) throws IOException {
      super(columnId, new DecimalTreeReader(columnId, fileType.getPrecision(),
          fileType.getScale(), context));
      this.precision = fileType.getPrecision();
      this.scale = fileType.getScale();
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      Timestamp timestampValue =
            TimestampUtils.decimalToTimestamp(
                decimalColVector.vector[elementNum].getHiveDecimal());
      // The TimestampColumnVector will set the entry to null when a null timestamp is passed in.
      timestampColVector.set(elementNum, timestampValue);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (decimalColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        decimalColVector = new DecimalColumnVector(precision, scale);
        timestampColVector = (TimestampColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(decimalColVector, isNull, batchSize);

      convertVector(decimalColVector, timestampColVector, batchSize);
    }
  }

  public static class TimestampFromStringGroupTreeReader extends ConvertTreeReader {
    private BytesColumnVector bytesColVector;
    private TimestampColumnVector timestampColVector;

    TimestampFromStringGroupTreeReader(int columnId, TypeDescription fileType, Context context)
        throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) throws IOException {
      String stringValue =
          stringFromBytesColumnVectorEntry(bytesColVector, elementNum);
      Timestamp timestampValue = parseTimestampFromString(stringValue);
      if (timestampValue != null) {
        timestampColVector.set(elementNum, timestampValue);
      } else {
        timestampColVector.noNulls = false;
        timestampColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (bytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        bytesColVector = new BytesColumnVector();
        timestampColVector = (TimestampColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(bytesColVector, isNull, batchSize);

      convertVector(bytesColVector, timestampColVector, batchSize);
    }
  }

  public static class TimestampFromDateTreeReader extends ConvertTreeReader {
    private LongColumnVector longColVector;
    private TimestampColumnVector timestampColVector;

    TimestampFromDateTreeReader(int columnId, TypeDescription fileType,
        Context context) throws IOException {
      super(columnId, new DateTreeReader(columnId, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      long millis =
          DateWritable.daysToMillis((int) longColVector.vector[elementNum]);
      timestampColVector.set(elementNum, new Timestamp(millis));
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (longColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        longColVector = new LongColumnVector();
        timestampColVector = (TimestampColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(longColVector, isNull, batchSize);

      convertVector(longColVector, timestampColVector, batchSize);
    }
  }

  public static class DateFromStringGroupTreeReader extends ConvertTreeReader {
    private BytesColumnVector bytesColVector;
    private LongColumnVector longColVector;

    DateFromStringGroupTreeReader(int columnId, TypeDescription fileType, Context context)
        throws IOException {
      super(columnId, getStringGroupTreeReader(columnId, fileType, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      String stringValue =
          stringFromBytesColumnVectorEntry(bytesColVector, elementNum);
      Date dateValue = parseDateFromString(stringValue);
      if (dateValue != null) {
        longColVector.vector[elementNum] = DateWritable.dateToDays(dateValue);
      } else {
        longColVector.noNulls = false;
        longColVector.isNull[elementNum] = true;
      }
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (bytesColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        bytesColVector = new BytesColumnVector();
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(bytesColVector, isNull, batchSize);

      convertVector(bytesColVector, longColVector, batchSize);
    }
  }

  public static class DateFromTimestampTreeReader extends ConvertTreeReader {
    private TimestampColumnVector timestampColVector;
    private LongColumnVector longColVector;

    DateFromTimestampTreeReader(int columnId, Context context) throws IOException {
      super(columnId, new TimestampTreeReader(columnId, context));
    }

    @Override
    public void setConvertVectorElement(int elementNum) {
      Date dateValue =
          DateWritable.timeToDate(TimestampUtils.millisToSeconds(
              timestampColVector.asScratchTimestamp(elementNum).getTime()));
      longColVector.vector[elementNum] = DateWritable.dateToDays(dateValue);
    }

    @Override
    public void nextVector(ColumnVector previousVector,
                           boolean[] isNull,
                           final int batchSize) throws IOException {
      if (timestampColVector == null) {
        // Allocate column vector for file; cast column vector for reader.
        timestampColVector = new TimestampColumnVector();
        longColVector = (LongColumnVector) previousVector;
      }
      // Read present/isNull stream
      fromReader.nextVector(timestampColVector, isNull, batchSize);

      convertVector(timestampColVector, longColVector, batchSize);
    }
  }

  private static TreeReader createAnyIntegerConvertTreeReader(int columnId,
                                                              TypeDescription fileType,
                                                              TypeDescription readerType,
                                                              Context context) throws IOException {

    // CONVERT from (BOOLEAN, BYTE, SHORT, INT, LONG) to schema type.
    //
    switch (readerType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      if (fileType.getCategory() == readerType.getCategory()) {
        throw new IllegalArgumentException("No conversion of type " +
            readerType.getCategory() + " to self needed");
      }
      return new AnyIntegerFromAnyIntegerTreeReader(columnId, fileType, readerType,
          context);

    case FLOAT:
    case DOUBLE:
      return new DoubleFromAnyIntegerTreeReader(columnId, fileType,
        context);

    case DECIMAL:
      return new DecimalFromAnyIntegerTreeReader(columnId, fileType, context);

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromAnyIntegerTreeReader(columnId, fileType, readerType,
        context);

    case TIMESTAMP:
      return new TimestampFromAnyIntegerTreeReader(columnId, fileType, context);

    // Not currently supported conversion(s):
    case BINARY:
    case DATE:

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createDoubleConvertTreeReader(int columnId,
                                                          TypeDescription fileType,
                                                          TypeDescription readerType,
                                                          Context context) throws IOException {

    // CONVERT from DOUBLE to schema type.
    switch (readerType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      return new AnyIntegerFromDoubleTreeReader(columnId, fileType, readerType);

    case FLOAT:
      return new FloatFromDoubleTreeReader(columnId, context);

    case DOUBLE:
      return new FloatTreeReader(columnId);

    case DECIMAL:
      return new DecimalFromDoubleTreeReader(columnId, fileType, readerType);

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromDoubleTreeReader(columnId, fileType, readerType, context);

    case TIMESTAMP:
      return new TimestampFromDoubleTreeReader(columnId, fileType, readerType, context);

    // Not currently supported conversion(s):
    case BINARY:
    case DATE:

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createDecimalConvertTreeReader(int columnId,
                                                           TypeDescription fileType,
                                                           TypeDescription readerType,
                                                           Context context) throws IOException {

    // CONVERT from DECIMAL to schema type.
    switch (readerType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      return new AnyIntegerFromDecimalTreeReader(columnId, fileType, readerType, context);

    case FLOAT:
    case DOUBLE:
      return new DoubleFromDecimalTreeReader(columnId, fileType, context);

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromDecimalTreeReader(columnId, fileType, readerType, context);

    case TIMESTAMP:
      return new TimestampFromDecimalTreeReader(columnId, fileType, context);

    case DECIMAL:
      return new DecimalFromDecimalTreeReader(columnId, fileType, readerType, context);

    // Not currently supported conversion(s):
    case BINARY:
    case DATE:

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createStringConvertTreeReader(int columnId,
                                                          TypeDescription fileType,
                                                          TypeDescription readerType,
                                                          Context context) throws IOException {

    // CONVERT from STRING to schema type.
    switch (readerType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      return new AnyIntegerFromStringGroupTreeReader(columnId, fileType, readerType, context);

    case FLOAT:
    case DOUBLE:
      return new DoubleFromStringGroupTreeReader(columnId, fileType, context);

    case DECIMAL:
      return new DecimalFromStringGroupTreeReader(columnId, fileType, readerType, context);

    case CHAR:
      return new StringGroupFromStringGroupTreeReader(columnId, fileType, readerType, context);

    case VARCHAR:
      return new StringGroupFromStringGroupTreeReader(columnId, fileType, readerType, context);

    case STRING:
      throw new IllegalArgumentException("No conversion of type " +
          readerType.getCategory() + " to self needed");

    case BINARY:
      return new BinaryTreeReader(columnId, context);

    case TIMESTAMP:
      return new TimestampFromStringGroupTreeReader(columnId, fileType, context);

    case DATE:
      return new DateFromStringGroupTreeReader(columnId, fileType, context);

    // Not currently supported conversion(s):

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createTimestampConvertTreeReader(int columnId,
                                                             TypeDescription readerType,
                                                             Context context) throws IOException {

    // CONVERT from TIMESTAMP to schema type.
    switch (readerType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      return new AnyIntegerFromTimestampTreeReader(columnId, readerType, context);

    case FLOAT:
    case DOUBLE:
      return new DoubleFromTimestampTreeReader(columnId, context);

    case DECIMAL:
      return new DecimalFromTimestampTreeReader(columnId, context);

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromTimestampTreeReader(columnId, readerType, context);

    case TIMESTAMP:
      throw new IllegalArgumentException("No conversion of type " +
          readerType.getCategory() + " to self needed");

    case DATE:
      return new DateFromTimestampTreeReader(columnId, context);

    // Not currently supported conversion(s):
    case BINARY:

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createDateConvertTreeReader(int columnId,
                                                        TypeDescription readerType,
                                                        Context context) throws IOException {

    // CONVERT from DATE to schema type.
    switch (readerType.getCategory()) {

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromDateTreeReader(columnId, readerType, context);

    case TIMESTAMP:
      return new TimestampFromDateTreeReader(columnId, readerType, context);

    case DATE:
      throw new IllegalArgumentException("No conversion of type " +
          readerType.getCategory() + " to self needed");

      // Not currently supported conversion(s):
    case BOOLEAN:
    case BYTE:
    case FLOAT:
    case SHORT:
    case INT:
    case LONG:
    case DOUBLE:
    case BINARY:
    case DECIMAL:

    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  private static TreeReader createBinaryConvertTreeReader(int columnId,
                                                          TypeDescription readerType,
                                                          Context context) throws IOException {

    // CONVERT from DATE to schema type.
    switch (readerType.getCategory()) {

    case STRING:
    case CHAR:
    case VARCHAR:
      return new StringGroupFromBinaryTreeReader(columnId, readerType, context);

    case BINARY:
      throw new IllegalArgumentException("No conversion of type " +
          readerType.getCategory() + " to self needed");

      // Not currently supported conversion(s):
    case BOOLEAN:
    case BYTE:
    case FLOAT:
    case SHORT:
    case INT:
    case LONG:
    case DOUBLE:
    case TIMESTAMP:
    case DECIMAL:
    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          readerType.getCategory());
    }
  }

  /**
   * (Rules from Hive's PrimitiveObjectInspectorUtils conversion)
   *
   * To BOOLEAN, BYTE, SHORT, INT, LONG:
   *   Convert from (BOOLEAN, BYTE, SHORT, INT, LONG) with down cast if necessary.
   *   Convert from (FLOAT, DOUBLE) using type cast to long and down cast if necessary.
   *   Convert from DECIMAL from longValue and down cast if necessary.
   *   Convert from STRING using LazyLong.parseLong and down cast if necessary.
   *   Convert from (CHAR, VARCHAR) from Integer.parseLong and down cast if necessary.
   *   Convert from TIMESTAMP using timestamp getSeconds and down cast if necessary.
   *
   *   AnyIntegerFromAnyIntegerTreeReader (written)
   *   AnyIntegerFromFloatTreeReader (written)
   *   AnyIntegerFromDoubleTreeReader (written)
   *   AnyIntegerFromDecimalTreeReader (written)
   *   AnyIntegerFromStringGroupTreeReader (written)
   *   AnyIntegerFromTimestampTreeReader (written)
   *
   * To FLOAT/DOUBLE:
   *   Convert from (BOOLEAN, BYTE, SHORT, INT, LONG) using cast
   *   Convert from FLOAT using cast
   *   Convert from DECIMAL using getDouble
   *   Convert from (STRING, CHAR, VARCHAR) using Double.parseDouble
   *   Convert from TIMESTAMP using timestamp getDouble
   *
   *   FloatFromAnyIntegerTreeReader (existing)
   *   FloatFromDoubleTreeReader (written)
   *   FloatFromDecimalTreeReader (written)
   *   FloatFromStringGroupTreeReader (written)
   *
   *   DoubleFromAnyIntegerTreeReader (existing)
   *   DoubleFromFloatTreeReader (existing)
   *   DoubleFromDecimalTreeReader (written)
   *   DoubleFromStringGroupTreeReader (written)
   *
   * To DECIMAL:
   *   Convert from (BOOLEAN, BYTE, SHORT, INT, LONG) using to HiveDecimal.create()
   *   Convert from (FLOAT, DOUBLE) using to HiveDecimal.create(string value)
   *   Convert from (STRING, CHAR, VARCHAR) using HiveDecimal.create(string value)
   *   Convert from TIMESTAMP using HiveDecimal.create(string value of timestamp getDouble)
   *
   *   DecimalFromAnyIntegerTreeReader (existing)
   *   DecimalFromFloatTreeReader (existing)
   *   DecimalFromDoubleTreeReader (existing)
   *   DecimalFromStringGroupTreeReader (written)
   *
   * To STRING, CHAR, VARCHAR:
   *   Convert from (BOOLEAN, BYTE, SHORT, INT, LONG) using to string conversion
   *   Convert from (FLOAT, DOUBLE) using to string conversion
   *   Convert from DECIMAL using HiveDecimal.toString
   *   Convert from CHAR by stripping pads
   *   Convert from VARCHAR with value
   *   Convert from TIMESTAMP using Timestamp.toString
   *   Convert from DATE using Date.toString
   *   Convert from BINARY using Text.decode
   *
   *   StringGroupFromAnyIntegerTreeReader (written)
   *   StringGroupFromFloatTreeReader (written)
   *   StringGroupFromDoubleTreeReader (written)
   *   StringGroupFromDecimalTreeReader (written)
   *
   *   String from Char/Varchar conversion
   *   Char from String/Varchar conversion
   *   Varchar from String/Char conversion
   *
   *   StringGroupFromTimestampTreeReader (written)
   *   StringGroupFromDateTreeReader (written)
   *   StringGroupFromBinaryTreeReader *****
   *
   * To TIMESTAMP:
   *   Convert from (BOOLEAN, BYTE, SHORT, INT, LONG) using TimestampWritable.longToTimestamp
   *   Convert from (FLOAT, DOUBLE) using TimestampWritable.doubleToTimestamp
   *   Convert from DECIMAL using TimestampWritable.decimalToTimestamp
   *   Convert from (STRING, CHAR, VARCHAR) using string conversion
   *   Or, from DATE
   *
   *   TimestampFromAnyIntegerTreeReader (written)
   *   TimestampFromFloatTreeReader (written)
   *   TimestampFromDoubleTreeReader (written)
   *   TimestampFromDecimalTreeeReader (written)
   *   TimestampFromStringGroupTreeReader (written)
   *   TimestampFromDateTreeReader
   *
   *
   * To DATE:
   *   Convert from (STRING, CHAR, VARCHAR) using string conversion.
   *   Or, from TIMESTAMP.
   *
   *  DateFromStringGroupTreeReader (written)
   *  DateFromTimestampTreeReader (written)
   *
   * To BINARY:
   *   Convert from (STRING, CHAR, VARCHAR) using getBinaryFromText
   *
   *  BinaryFromStringGroupTreeReader (written)
   *
   * (Notes from StructConverter)
   *
   * To STRUCT:
   *   Input must be data type STRUCT
   *   minFields = Math.min(numSourceFields, numTargetFields)
   *   Convert those fields
   *   Extra targetFields to NULL
   *
   * (Notes from ListConverter)
   *
   * To LIST:
   *   Input must be data type LIST
   *   Convert elements
   *
   * (Notes from MapConverter)
   *
   * To MAP:
   *   Input must be data type MAP
   *   Convert keys and values
   *
   * (Notes from UnionConverter)
   *
   * To UNION:
   *   Input must be data type UNION
   *   Convert value for tag
   *
   * @param readerType
   * @return
   * @throws IOException
   */
  public static TreeReader createConvertTreeReader(TypeDescription readerType,
                                                   Context context) throws IOException {
    final SchemaEvolution evolution = context.getSchemaEvolution();

    TypeDescription fileType = evolution.getFileType(readerType.getId());
    int columnId = fileType.getId();

    switch (fileType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
      return createAnyIntegerConvertTreeReader(columnId, fileType, readerType, context);

    case FLOAT:
    case DOUBLE:
      return createDoubleConvertTreeReader(columnId, fileType, readerType, context);

    case DECIMAL:
      return createDecimalConvertTreeReader(columnId, fileType, readerType, context);

    case STRING:
    case CHAR:
    case VARCHAR:
      return createStringConvertTreeReader(columnId, fileType, readerType, context);

    case TIMESTAMP:
      return createTimestampConvertTreeReader(columnId, readerType, context);

    case DATE:
      return createDateConvertTreeReader(columnId, readerType, context);

    case BINARY:
      return createBinaryConvertTreeReader(columnId, readerType, context);

    // UNDONE: Complex conversions...
    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
    default:
      throw new IllegalArgumentException("Unsupported type " +
          fileType.getCategory());
    }
  }

  public static boolean canConvert(TypeDescription fileType, TypeDescription readerType) {

    Category readerTypeCategory = readerType.getCategory();

    // We don't convert from any to complex.
    switch (readerTypeCategory) {
    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
      return false;

    default:
      // Fall through.
    }

    // Now look for the few cases we don't convert from
    switch (fileType.getCategory()) {

    case BOOLEAN:
    case BYTE:
    case SHORT:
    case INT:
    case LONG:
    case FLOAT:
    case DOUBLE:
    case DECIMAL:
      switch (readerType.getCategory()) {
      // Not currently supported conversion(s):
      case BINARY:
      case DATE:
        return false;
      default:
        return true;
      }


    case STRING:
    case CHAR:
    case VARCHAR:
      switch (readerType.getCategory()) {
      // Not currently supported conversion(s):
        // (None)
      default:
        return true;
      }

    case TIMESTAMP:
      switch (readerType.getCategory()) {
      // Not currently supported conversion(s):
      case BINARY:
        return false;
      default:
        return true;
      }

    case DATE:
      switch (readerType.getCategory()) {
      // Not currently supported conversion(s):
      case BOOLEAN:
      case BYTE:
      case FLOAT:
      case SHORT:
      case INT:
      case LONG:
      case DOUBLE:
      case BINARY:
      case DECIMAL:
        return false;
      default:
        return true;
      }

    case BINARY:
      switch (readerType.getCategory()) {
      // Not currently supported conversion(s):
      case BOOLEAN:
      case BYTE:
      case FLOAT:
      case SHORT:
      case INT:
      case LONG:
      case DOUBLE:
      case TIMESTAMP:
      case DECIMAL:
        return false;
      default:
        return true;
      }

    // We don't convert from complex to any.
    case STRUCT:
    case LIST:
    case MAP:
    case UNION:
      return false;

    default:
      throw new IllegalArgumentException("Unsupported type " +
          fileType.getCategory());
    }
  }
}
