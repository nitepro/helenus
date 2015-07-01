/*
 * Copyright (C) 2015-2015 The Helenus Driver Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.helenus.driver.impl;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.net.InetAddress;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

import org.apache.commons.lang3.LocaleUtils;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.exceptions.InvalidTypeException;
import com.datastax.driver.core.utils.Bytes;

import org.helenus.driver.StatementBuilder;
import org.helenus.driver.info.ClassInfo;

/**
 * The <code>DataDecoder</code> abstract class defines the decoding functionality
 * for a column.
 * <p>
 * Supported conversions are:
 * - "ascii"     to {@link Enum}, {@link Class}, {@link Locale}, {@link ZoneId}, or {@link String}
 * - "bigint"    to {@link Long}
 * - "blob"      to <code>byte[]</code> or {@link ByteBuffer}
 * - "boolean"   to {@link Boolean}
 * - "counter"   to {@link AtomicLong} or {@link Long}
 * - "decimal"   to {@link BigDecimal}
 * - "double"    to {@link Double}
 * - "float"     to {@link Float}
 * - "inet"      to {@link InetAddress}
 * - "int"       to {@link Integer}
 * - "text"      to {@link String}
 * - "timestamp" to {@link Date}, {@link Instant}, or {@link Long}
 * - "uuid"      to {@link UUID}
 * - "varchar"   to {@link String}
 * - "varint"    to {@link BigInteger}
 * - "timeuuid"  to {@link UUID}
 *
 * - "list&lt;ctype&gt;"       to {@link List} of the corresponding element type
 * - "map&lt;ctype, ctype&gt;" to {@link Map} of the corresponding element types
 * - "set&lt;ctype&gt;"        to {@link Set} of the corresponding element type
 * - "frozen&lt;udt&gt;"       to an object of the user-defined type
 *
 * @copyright 2015-2015 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Jan 19, 2015 - paouelle - Creation
 *
 * @param <V> the type of data being decoded
 *
 * @since 1.0
 */
public abstract class DataDecoder<V> {
  /**
   * Holds the "ascii" to {@link String} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<String> asciiToString = new DataDecoder<String>(String.class) {
    @Override
    protected String decodeImpl(Row row, String name, Class<String> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        String.class == clazz,
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getString(name);
    }
    @Override
    protected String decodeImpl(UDTValue uval, String name, Class<String> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        String.class == clazz,
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getString(name);
    }
  };

  /**
   * Holds the "ascii" to {@link Enum} decoder.
   *
   * @author paouelle
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<Enum> asciiToEnum = new DataDecoder<Enum>(Enum.class) {
    @SuppressWarnings("unchecked")
    @Override
    protected Enum decodeImpl(Row row, String name, Class<Enum> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Enum.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String eval = row.getString(name);

      return (eval != null) ? Enum.valueOf(clazz, eval) : null;
    }
    @SuppressWarnings("unchecked")
    @Override
    protected Enum decodeImpl(UDTValue uval, String name, Class<Enum> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Enum.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String eval = uval.getString(name);

      return (eval != null) ? Enum.valueOf(clazz, eval) : null;
    }
  };

  /**
   * Holds the "ascii" to {@link Class} decoder.
   *
   * @author paouelle
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<Class> asciiToClass = new DataDecoder<Class>(Class.class) {
    @Override
    protected Class decodeImpl(Row row, String name, Class<Class> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Class.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String cname = row.getString(name);

      if (cname == null) {
        return null;
      }
      try {
        return DataDecoder.findClass(cname);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      }
    }
    @Override
    protected Class decodeImpl(UDTValue uval, String name, Class<Class> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Class.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String cname = uval.getString(name);

      if (cname == null) {
        return null;
      }
      try {
        return DataDecoder.findClass(cname);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      }
    }
  };

  /**
   * Holds the "ascii" to {@link Locale} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Locale> asciiToLocale = new DataDecoder<Locale>(Locale.class) {
    @Override
    protected Locale decodeImpl(Row row, String name, Class<Locale> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Locale.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String lname = row.getString(name);

      return (lname != null) ? LocaleUtils.toLocale(lname) : null;
    }
    @Override
    protected Locale decodeImpl(UDTValue uval, String name, Class<Locale> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Locale.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String lname = uval.getString(name);

      return (lname != null) ? LocaleUtils.toLocale(lname) : null;
    }
  };

  /**
   * Holds the "ascii" to {@link ZoneId} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<ZoneId> asciiToZoneId = new DataDecoder<ZoneId>(ZoneId.class) {
    @Override
    protected ZoneId decodeImpl(Row row, String name, Class<ZoneId> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        ZoneId.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String zid = row.getString(name);

      try {
        return (zid != null) ? ZoneId.of(zid) : null;
      } catch (DateTimeException e) {
        throw new IllegalArgumentException(e);
      }
    }
    @Override
    protected ZoneId decodeImpl(UDTValue uval, String name, Class<ZoneId> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        ZoneId.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final String zid = uval.getString(name);

      try {
        return (zid != null) ? ZoneId.of(zid) : null;
      } catch (DateTimeException e) {
        throw new IllegalArgumentException(e);
      }
    }
  };

  /**
   * Holds the "bigint" to {@link Long} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Long> bigintToLong = new DataDecoder<Long>(Long.class) {
    @Override
    protected Long decodeImpl(Row row, String name, Class<Long> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Long.class == clazz) || (Long.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : row.getLong(name);
    }
    @Override
    protected Long decodeImpl(UDTValue uval, String name, Class<Long> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Long.class == clazz) || (Long.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : uval.getLong(name);
    }
  };

  /**
   * Holds the "blob" to <code>byte[]</code> decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<byte[]> blobToByteArray = new DataDecoder<byte[]>(byte[].class) {
    @Override
    protected byte[] decodeImpl(Row row, String name, Class<byte[]> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        clazz.isArray(),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      org.apache.commons.lang3.Validate.isTrue(
        Byte.TYPE == clazz.getComponentType(),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final ByteBuffer buf = row.getBytes(name);

      return (buf != null) ? Bytes.getArray(buf) : null;
    }
    @Override
    protected byte[] decodeImpl(UDTValue uval, String name, Class<byte[]> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        clazz.isArray(),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      org.apache.commons.lang3.Validate.isTrue(
        Byte.TYPE == clazz.getComponentType(),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final ByteBuffer buf = uval.getBytes(name);

      return (buf != null) ? Bytes.getArray(buf) : null;
    }
  };

  /**
   * Holds the "blob" to {@link ByteBuffer} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<ByteBuffer> blobToByteBuffer = new DataDecoder<ByteBuffer>(ByteBuffer.class) {
    @Override
    protected ByteBuffer decodeImpl(Row row, String name, Class<ByteBuffer> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        ByteBuffer.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getBytes(name);
    }
    @Override
    protected ByteBuffer decodeImpl(UDTValue uval, String name, Class<ByteBuffer> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        ByteBuffer.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getBytes(name);
    }
  };

  /**
   * Holds the "boolean" to {@link Boolean} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Boolean> booleanToBoolean = new DataDecoder<Boolean>(Boolean.class) {
    @Override
    protected Boolean decodeImpl(Row row, String name, Class<Boolean> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Boolean.class == clazz) || (Boolean.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : row.getBool(name);
    }
    @Override
    protected Boolean decodeImpl(UDTValue uval, String name, Class<Boolean> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Boolean.class == clazz) || (Boolean.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : uval.getBool(name);
    }
  };

  /**
   * Holds the "counter" to {@link Long} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Long> counterToLong = DataDecoder.bigintToLong;

  /**
   * Holds the "counter" to {@link AtomicLong} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<AtomicLong> counterToAtomicLong = new DataDecoder<AtomicLong>(AtomicLong.class) {
    @Override
    protected AtomicLong decodeImpl(Row row, String name, Class<AtomicLong> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        AtomicLong.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : new AtomicLong(row.getLong(name));
    }
    @Override
    protected AtomicLong decodeImpl(UDTValue uval, String name, Class<AtomicLong> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        AtomicLong.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : new AtomicLong(uval.getLong(name));
    }
  };

  /**
   * Holds the "decimal" to {@link BigDecimal} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<BigDecimal> decimalToBigDecimal = new DataDecoder<BigDecimal>(BigDecimal.class) {
    @Override
    protected BigDecimal decodeImpl(Row row, String name, Class<BigDecimal> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        BigDecimal.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getDecimal(name);
    }
    @Override
    protected BigDecimal decodeImpl(UDTValue uval, String name, Class<BigDecimal> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        BigDecimal.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getDecimal(name);
    }
  };

  /**
   * Holds the "double" to {@link Double} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Double> doubleToDouble = new DataDecoder<Double>(Double.class) {
    @Override
    protected Double decodeImpl(Row row, String name, Class<Double> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Double.class == clazz) || (Double.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : row.getDouble(name);
    }
    @Override
    protected Double decodeImpl(UDTValue uval, String name, Class<Double> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Double.class == clazz) || (Double.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : uval.getDouble(name);
    }
  };

  /**
   * Holds the "float" to {@link Float} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Float> floatToFloat = new DataDecoder<Float>(Float.class) {
    @Override
    protected Float decodeImpl(Row row, String name, Class<Float> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Float.class == clazz) || (Float.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : row.getFloat(name);
    }
    @Override
    protected Float decodeImpl(UDTValue uval, String name, Class<Float> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Float.class == clazz) || (Float.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : uval.getFloat(name);
    }
  };

  /**
   * Holds the "inet" to {@link InetAddress} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<InetAddress> inetToInetAddress = new DataDecoder<InetAddress>(InetAddress.class) {
    @Override
    protected InetAddress decodeImpl(Row row, String name, Class<InetAddress> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        InetAddress.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getInet(name);
    }
    @Override
    protected InetAddress decodeImpl(UDTValue uval, String name, Class<InetAddress> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        InetAddress.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getInet(name);
    }
  };

  /**
   * Holds the "int" to {@link Integer} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Integer> intToInteger = new DataDecoder<Integer>(Integer.class) {
    @Override
    protected Integer decodeImpl(Row row, String name, Class<Integer> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Integer.class == clazz) || (Integer.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.isNull(name) ? null : row.getInt(name);
    }
    @Override
    protected Integer decodeImpl(UDTValue uval, String name, Class<Integer> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Integer.class == clazz) || (Integer.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.isNull(name) ? null : uval.getInt(name);
    }
  };

  /**
   * Holds the "text" to {@link String} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<String> textToString = DataDecoder.asciiToString;

  /**
   * Holds the "timestamp" to {@link Date} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Date> timestampToDate = new DataDecoder<Date>(Date.class) {
    @Override
    protected Date decodeImpl(Row row, String name, Class<Date> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Date.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getDate(name);
    }
    @Override
    protected Date decodeImpl(UDTValue uval, String name, Class<Date> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Date.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getDate(name);
    }
  };

  /**
   * Holds the "timestamp" to {@link Instant} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Instant> timestampToInstant = new DataDecoder<Instant>(Instant.class) {
    @Override
    protected Instant decodeImpl(Row row, String name, Class<Instant> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Instant.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final Date date = row.getDate(name);

      return (date != null) ? date.toInstant() : null;
    }
    @Override
    protected Instant decodeImpl(UDTValue uval, String name, Class<Instant> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        Instant.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final Date date = uval.getDate(name);

      return (date != null) ? date.toInstant() : null;
    }
  };

  /**
   * Holds the "timestamp" to {@link Long} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<Long> timestampToLong = new DataDecoder<Long>(Long.class) {
    @Override
    protected Long decodeImpl(Row row, String name, Class<Long> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Long.class == clazz) || (Long.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final Date date = row.getDate(name);

      return (date != null) ? date.getTime() : null;
    }
    @Override
    protected Long decodeImpl(UDTValue uval, String name, Class<Long> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        (Long.class == clazz) || (Long.TYPE == clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      final Date date = uval.getDate(name);

      return (date != null) ? date.getTime() : null;
    }
  };

  /**
   * Holds the "uuid" to {@link UUID} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<UUID> uuidToUUID = new DataDecoder<UUID>(UUID.class) {
    @Override
    protected UUID decodeImpl(Row row, String name, Class<UUID> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        UUID.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getUUID(name);
    }
    @Override
    protected UUID decodeImpl(UDTValue uval, String name, Class<UUID> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        UUID.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getUUID(name);
    }
  };

  /**
   * Holds the "varchar" to {@link String} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<String> varcharToString = DataDecoder.asciiToString;

  /**
   * Holds the "varint" to {@link BigInteger} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<BigInteger> varintToBigInteger = new DataDecoder<BigInteger>(BigInteger.class) {
    @Override
    protected BigInteger decodeImpl(Row row, String name, Class<BigInteger> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        BigInteger.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return row.getVarint(name);
    }
    @Override
    protected BigInteger decodeImpl(UDTValue uval, String name, Class<BigInteger> clazz) {
      org.apache.commons.lang3.Validate.isTrue(
        BigInteger.class.isAssignableFrom(clazz),
        "unsupported class '%s' to decode to",
        clazz.getName()
      );
      return uval.getVarint(name);
    }
  };

  /**
   * Holds the "timeuuid" to {@link UUID} decoder.
   *
   * @author paouelle
   */
  public final static DataDecoder<UUID> timeuuidToUUID = DataDecoder.uuidToUUID;

  /**
   * Finds and loads the specified class.
   * <p>
   * <i>Note:</i> This method is designed to use the thread context class loader
   * if set; otherwise it falls back to the primordial class loader.
   *
   * @author paouelle
   *
   * @param  name the name of the class to find and load
   * @return the corresponding non-<code>null</code> class
   * @throws LinkageError if the linkage fails
   * @throws ExceptionInInitializerError if the initialization provoked by this
   *         method fails
   * @throws ClassNotFoundException if the class cannot be located
   */
  public static Class<?> findClass(String name) throws ClassNotFoundException {
    final ClassLoader otccl = Thread.currentThread().getContextClassLoader();

    return (otccl != null) ? Class.forName(name, true, otccl) : Class.forName(name);
  }

  /**
   * Gets a "list" to {@link List} decoder based on the given element class.
   *
   * @author paouelle
   *
   * @param  eclazz the non-<code>null</code> class of elements
   * @param  mandatory if the field associated with the decoder is mandatory or
   *         represents a primary key
   * @return the non-<code>null</code> decoder for lists of the specified element
   *         class
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<List> list(
    final Class<?> eclazz, final boolean mandatory
  ) {
    return new DataDecoder<List>(List.class) {
      @SuppressWarnings("unchecked")
      private List decodeImpl(Class<?> etype, List<Object> list) {
        if (list == null) {
          // safe to return as is unless mandatory, that is because Cassandra
          // returns null for empty lists and the schema definition requires
          // that mandatory and primary keys be non null
          if (mandatory) {
            return new ArrayList(8);
          }
          return list;
        }
        final List nlist = new ArrayList(list.size());

        if (eclazz.isAssignableFrom(etype)) {
          // we only need to store elements to make sure list is modifiable
          nlist.addAll(list);
        } else {
          // will need to do some conversion of each element
          final ElementConverter converter = ElementConverter.getConverter(eclazz, etype);

          for (final Object o: list) {
            nlist.add((o != null) ? converter.convert(o) : null);
          }
        }
        return nlist;
      }
      @SuppressWarnings("unchecked")
      @Override
      protected List decodeImpl(Row row, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          row.getColumnDefinitions().getType(name).getTypeArguments().get(0).getName().asJavaClass(),
          row.isNull(name) ? null : row.getList(name, Object.class) // keeps things generic so we can handle our own errors
        );
      }
      @SuppressWarnings("unchecked")
      @Override
      protected List decodeImpl(UDTValue uval, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          uval.getType().getFieldType(name).getTypeArguments().get(0).getName().asJavaClass(),
          uval.isNull(name) ? null : uval.getList(name, Object.class) // keeps things generic so we can handle our own errors
        );
      }
    };
  }

  /**
   * Gets a "set" to {@link Set} decoder based on the given element class.
   *
   * @author paouelle
   *
   * @param  eclazz the non-<code>null</code> class of elements
   * @param  mandatory if the field associated with the decoder is mandatory or
   *         represents a primary key
   * @return the non-<code>null</code> decoder for sets of the specified element
   *         class
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<Set> set(
    final Class<?> eclazz, final boolean mandatory
  ) {
    return new DataDecoder<Set>(Set.class) {
      @SuppressWarnings("unchecked")
      private Set decodeImpl(Class<?> etype, Set<Object> set) {
        if (set == null) {
          // safe to return as is unless mandatory, that is because Cassandra
          // returns null for empty sets and the schema definition requires
          // that mandatory and primary keys be non null
          if (mandatory) {
            if (eclazz.isEnum()) {
              // for enum values we create an enum set. Now we won't preserve the order
              // the entries were added but that should be fine anyways in this case
              return EnumSet.noneOf((Class<? extends Enum>)eclazz);
            }
            return new LinkedHashSet(8); // to keep order
          }
          return set;
        }
        final Set nset;

        if (eclazz.isEnum()) {
          // for enum values we create an enum set. Now we won't preserve the order
          // the entries were added but that should be fine anyways in this case
          nset = EnumSet.noneOf((Class<? extends Enum>)eclazz);
        } else {
          nset = new LinkedHashSet(set.size()); // to keep order
        }
        if (eclazz.isAssignableFrom(etype)) {
          // we only need to store elements to make sure list is modifiable
          nset.addAll(set);
        } else {
          // will need to do some conversion of each element
          final ElementConverter converter = ElementConverter.getConverter(eclazz, etype);

          for (final Object o: set) {
            nset.add((o != null) ? converter.convert(o) : null);
          }
        }
        return nset;
      }
      @SuppressWarnings("unchecked")
      @Override
      protected Set decodeImpl(Row row, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          row.getColumnDefinitions().getType(name).getTypeArguments().get(0).getName().asJavaClass(),
          row.isNull(name) ? null : row.getSet(name, Object.class) // keeps things generic so we can handle our own errors
        );
      }
      @SuppressWarnings("unchecked")
      @Override
      protected Set decodeImpl(UDTValue uval, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          uval.getType().getFieldType(name).getTypeArguments().get(0).getName().asJavaClass(),
          uval.isNull(name) ? null : uval.getSet(name, Object.class) // keeps things generic so we can handle our own errors
        );
      }
    };
  }

  /**
   * Gets a "map" to {@link Map} decoder based on the given key and value classes.
   *
   * @author paouelle
   *
   * @param  ekclazz the non-<code>null</code> class of keys
   * @param  evclazz the non-<code>null</code> class of values
   * @param  mandatory if the field associated with the decoder is mandatory or
   *         represents a primary key
   * @return the non-<code>null</code> decoder for maps of the specified key and
   *         value classes
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<Map> map(
    final Class<?> ekclazz, final Class<?> evclazz, final boolean mandatory
  ) {
    return new DataDecoder<Map>(Map.class) {
      @SuppressWarnings("unchecked")
      private Map decodeImpl(Class<?> ektype, Class<?> evtype, Map<Object, Object> map) {
        if (map == null) {
          // safe to return as is unless mandatory, that is because Cassandra
          // returns null for empty list and the schema definition requires
          // that mandatory and primary keys be non null
          if (mandatory) {
            if (ekclazz.isEnum()) {
              // for enum keys we create an enum map. Now we won't preserve the order
              // the entries were added but that should be fine anyways in this case
              return new EnumMap(ekclazz);
            }
            return new LinkedHashMap(8); // to keep order
          }
          return map;
        }
        final Map nmap;

        if (ekclazz.isEnum()) {
          // for enum keys we create an enum map. Now we won't preserve the order
          // the entries were added but that should be fine anyways in this case
          nmap = new EnumMap(ekclazz);
        } else {
          nmap = new LinkedHashMap(map.size()); // to keep order
        }
        if (ekclazz.isAssignableFrom(ektype) && evclazz.isAssignableFrom(evtype)) {
          nmap.putAll(map);
        } else {
          // will need to do some conversion of each element
          final ElementConverter kconverter = ElementConverter.getConverter(ekclazz, ektype);
          final ElementConverter vconverter = ElementConverter.getConverter(evclazz, evtype);

          for (final Map.Entry e: map.entrySet()) {
            final Object k = e.getKey();
            final Object v = e.getValue();

            nmap.put((k != null) ? kconverter.convert(k) : null, (v != null) ? vconverter.convert(v) : null);
          }
        }
        return nmap;
      }
      @SuppressWarnings("unchecked")
      @Override
      protected Map decodeImpl(Row row, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          row.getColumnDefinitions().getType(name).getTypeArguments().get(0).getName().asJavaClass(),
          row.getColumnDefinitions().getType(name).getTypeArguments().get(1).getName().asJavaClass(),
          row.isNull(name) ? null : row.getMap(name, Object.class, Object.class) // keeps things generic so we can handle our own errors
        );
      }
      @SuppressWarnings("unchecked")
      @Override
      protected Map decodeImpl(UDTValue uval, String name, Class clazz) {
        return decodeImpl(
          // get the element type from the row's metadata
          uval.getType().getFieldType(name).getTypeArguments().get(0).getName().asJavaClass(),
          uval.getType().getFieldType(name).getTypeArguments().get(1).getName().asJavaClass(),
          uval.isNull(name) ? null : uval.getMap(name, Object.class, Object.class) // keeps things generic so we can handle our own errors
        );
      }
    };
  }

  /**
   * Gets a "udt" to {@link Object} decoder based on the given UDT class info.
   *
   * @author paouelle
   *
   * @param  cinfo the user-defined class info
   * @return the non-<code>null</code> decoder for user-defined types represented
   *         by the given class info
   */
  @SuppressWarnings("rawtypes")
  public final static DataDecoder<Object> udt(final UDTClassInfoImpl<?> cinfo) {
    return new DataDecoder<Object>(Object.class) {
      @SuppressWarnings("unchecked")
      @Override
      protected Object decodeImpl(Row row, String name, Class clazz) {
        org.apache.commons.lang3.Validate.isTrue(
          clazz.isAssignableFrom(cinfo.getObjectClass()),
          "unsupported class '%s' to decode to", clazz.getName()
        );
        final UDTValue uval = row.getUDTValue(name);

        if (uval == null) {
          return null;
        }
        return cinfo.getObject(uval);
      }
      @SuppressWarnings("unchecked")
      @Override
      protected Object decodeImpl(UDTValue uval, String name, Class clazz) {
        org.apache.commons.lang3.Validate.isTrue(
          clazz.isAssignableFrom(cinfo.getObjectClass()),
          "unsupported class '%s' to decode to", clazz.getName()
        );
        final UDTValue fuval = uval.getUDTValue(name);

        if (fuval == null) {
          return null;
        }
        return cinfo.getObject(fuval);
      }
    };
  }

  /**
   * Holds the class this decoder decodes to.
   *
   * @author paouelle
   */
  private final Class<V> clazz;

  /**
   * Instantiates a new <code>DataDecoder</code> object.
   *
   * @author paouelle
   *
   * @param clazz the non-<code>null</code> class this decoder decodes to
   */
  DataDecoder(Class<V> clazz) {
    this.clazz = clazz;
  }

  /**
   * Decodes the specified column from the given row and return the value that
   * matches the decoder's defined class.
   *
   * @author paouelle
   *
   * @param  row the non-<code>null</code> row where the column value is defined
   * @param  name the non-<code>null</code> name of the column to decode
   * @param  clazz the non-<code>null</code> class to decode to
   * @return the decoded object
   * @throws IllegalArgumentException if decoding to <code>clazz</code> is not
   *         supported or if <code>name</code> is not part of the
   *         result set this row is part of
   * @throws InvalidTypeException if column <code>name</code> type is not the
   *         expected one
   */
  protected abstract V decodeImpl(Row row, String name, Class<V> clazz);

  /**
   * Decodes the specified column from the given UDT value and return the value
   * that matches the decoder's defined class.
   *
   * @author paouelle
   *
   * @param  uval the non-<code>null</code> UDT value where the column value is
   *         defined
   * @param  name the non-<code>null</code> name of the column to decode
   * @param  clazz the non-<code>null</code> class to decode to
   * @return the decoded object
   * @throws IllegalArgumentException if decoding to <code>clazz</code> is not
   *         supported or if <code>name</code> is not part of the
   *         UDT value
   * @throws InvalidTypeException if column <code>name</code> type is not the
   *         expected one
   */
  protected abstract V decodeImpl(UDTValue uval, String name, Class<V> clazz);

  /**
   * Checks if this decoder can decode to the specified class.
   *
   * @author paouelle
   *
   * @param  clazz the class to check if the decoder can decode to
   * @return <code>true</code> if this decoder can decode to the specified class;
   *         <code>false</code> otherwise
   */
  public boolean canDecodeTo(Class<?> clazz) {
    return this.clazz.isAssignableFrom(clazz);
  }

  /**
   * Decodes the specified column from the given row and return the value that
   * matches the decoder's defined class.
   *
   * @author paouelle
   *
   * @param  row the non-<code>null</code> row where the column value is defined
   * @param  name the non-<code>null</code> name of the column to decode
   * @param  clazz the class to decode to
   * @return the decoded object or <code>null</code> if the specified column
   *         doesn't exist in the rows
   * @throws NullPointerException if <code>row</code>, <code>name</code>, or
   *         <code>clazz</code> is <code>null</code>
   * @throws IllegalArgumentException if decoding to <code>clazz</code> is not
   *         supported or if <code>name</code> is not part of the
   *         result set this row is part of
   * @throws InvalidTypeException if column <code>name</code> type is not the
   *         expected one
   */
  public V decode(Row row, String name, Class<V> clazz) {
    org.apache.commons.lang3.Validate.notNull(row, "invalid null row");
    org.apache.commons.lang3.Validate.notNull(name, "invalid null name");
    org.apache.commons.lang3.Validate.notNull(clazz, "invalid null clazz");
    return decodeImpl(row, name, clazz);
  }

  /**
   * Decodes the specified column from the given UDT value and return the value
   * that matches the decoder's defined class.
   *
   * @author paouelle
   *
   * @param  uval the non-<code>null</code> UDT value where the column value is
   *         defined
   * @param  name the non-<code>null</code> name of the column to decode
   * @param  clazz the class to decode to
   * @return the decoded object or <code>null</code> if the specified column
   *         doesn't exist in the UDT value
   * @throws NullPointerException if <code>uval</code>, <code>name</code>, or
   *         <code>clazz</code> is <code>null</code>
   * @throws IllegalArgumentException if decoding to <code>clazz</code> is not
   *         supported or if <code>name</code> is not part of the UDT value
   * @throws InvalidTypeException if column <code>name</code> type is not the
   *         expected one
   */
  public V decode(UDTValue uval, String name, Class<V> clazz) {
    org.apache.commons.lang3.Validate.notNull(uval, "invalid null UDT value");
    org.apache.commons.lang3.Validate.notNull(name, "invalid null name");
    org.apache.commons.lang3.Validate.notNull(clazz, "invalid null clazz");
    return decodeImpl(uval, name, clazz);
  }
}

/**
 * The <code>ElementConverter</code> class defines a converting capability for
 * special supported combinations.
 *
 * @copyright 2015-2015 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Jan 19, 2015 - paouelle - Creation
 *
 * @since 1.0
 */
abstract class ElementConverter {
  /**
   * Converts the specified row element.
   *
   * @author paouelle
   *
   * @param  re the non-<code>null</code> row element to convert
   * @return the converted object
   * @throws IllegalArgumentException if unable to convert the row element
   */
  public abstract Object convert(Object re);

  /**
   * Gets an element decoder that can be used to convert a Cassandra
   * returned data type into another data type based on special supported
   * combinations.
   *
   * @author paouelle
   *
   * @param  eclass the non-<code>null</code> element class to decode to
   * @param  reclass the non-<code>null</code> row's element class
   * @return a non-<code>null</code> element decoder for the provided combination
   * @throws IllegalArgumentException if the combination is not supported
   */
  @SuppressWarnings("rawtypes")
  static ElementConverter getConverter(Class eclass, Class reclass) {
    if (UDTValue.class.isAssignableFrom(reclass)) { // special case for udt
      @SuppressWarnings("unchecked")
      final ClassInfo<?> cinfo = StatementBuilder.getClassInfo(eclass);

      org.apache.commons.lang3.Validate.isTrue(
        cinfo instanceof UDTClassInfoImpl,
        "unsupported element conversion from: %s to: %s; unknown user-defined type",
        reclass.getName(), eclass.getName()
      );
      final UDTClassInfoImpl<?> udtinfo = (UDTClassInfoImpl<?>)cinfo;

      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return udtinfo.getObject((UDTValue)re);
        }
      };
    } else if (Enum.class.isAssignableFrom(eclass) && (String.class == reclass)) {
      return new ElementConverter() {
        @SuppressWarnings("unchecked")
        @Override
        public Object convert(Object re) {
          return Enum.valueOf(eclass, (String)re);
        }
      };
    } else if (Class.class.isAssignableFrom(eclass) && (String.class == reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          try {
            return DataDecoder.findClass((String)re);
          } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
          }
        }
      };
    } else if (Locale.class.isAssignableFrom(eclass) && (String.class == reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return LocaleUtils.toLocale((String)re);
        }
      };
    } else if (ZoneId.class.isAssignableFrom(eclass) && (String.class == reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          try {
            return ZoneId.of((String)re);
          } catch (DateTimeException e) {
            throw new IllegalArgumentException(e);
          }
        }
      };
    } else if (eclass.isArray() && (Byte.TYPE == eclass.getComponentType()) && ByteBuffer.class.isAssignableFrom(reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return Bytes.getArray((ByteBuffer)re);
        }
      };
    } else if ((Long.class == eclass) && Date.class.isAssignableFrom(reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return ((Date)re).getTime();
        }
      };
    } else if ((Instant.class == eclass) && Date.class.isAssignableFrom(reclass)) {
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return ((Date)re).toInstant();
        }
      };
    } else if (eclass == reclass) { // special case for maps
      return new ElementConverter() {
        @Override
        public Object convert(Object re) {
          return re;
        }
      };
    }
    throw new IllegalArgumentException(
      "unsupported element conversion from: "
      + reclass.getName()
      + " to: "
      + eclass.getName()
    );
  }
}