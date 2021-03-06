/*
 * Copyright (C) 2015-2016 The Helenus Driver Project Authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import org.helenus.driver.Clause;
import org.helenus.driver.CreateType;
import org.helenus.driver.StatementBridge;
import org.helenus.driver.VoidFuture;

/**
 * The <code>CreateTypeImpl</code> class defines a CREATE TYPE statement.
 *
 * @copyright 2015-2016 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Mar 3, 2015 - paouelle - Creation
 *
 * @param <T> The type of POJO associated with this statement.
 *
 * @since 1.0
 */
public class CreateTypeImpl<T>
  extends GroupStatementImpl<Void, VoidFuture, T>
  implements CreateType<T> {
  /**
   * Holds the where statement part.
   *
   * @author paouelle
   */
  private final WhereImpl<T> where;

  /**
   * Flag indicating if the "IF NOT EXIST" option has been selected.
   *
   * @author paouelle
   */
  private volatile boolean ifNotExists;

  /**
   * Instantiates a new <code>CreateTypeImpl</code> object.
   *
   * @author paouelle
   *
   * @param context the non-<code>null</code> class info context for the POJO
   *        associated with this statement
   * @param mgr the non-<code>null</code> statement manager
   * @param bridge the non-<code>null</code> statement bridge
   */
  public CreateTypeImpl(
    ClassInfoImpl<T>.Context context,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    super(Void.class, context, mgr, bridge);
    this.where = new WhereImpl<>(this);
  }

  /**
   * Builds query strings for the specified table.
   *
   * @author paouelle
   *
   * @param  ucinfo the non-<code>null</code> UDT POJO class to build a query
   *         string
   * @return the string builders used to build the query strings for
   *         the specified table or <code>null</code> if there is none for the
   *         specified table
   * @throws IllegalArgumentException if the keyspace has not yet been computed
   *         and cannot be computed with the provided keyspace keys yet or if
   *         assignments reference columns not defined in the POJO or invalid
   *         values or if missing mandatory columns are referenced for the
   *         specified table
   */
  @SuppressWarnings("synthetic-access")
  protected StringBuilder[] buildQueryStrings(UDTClassInfoImpl<T> ucinfo) {
    final TableInfoImpl<T> table = ucinfo.getTableImpl();
    final List<String> columns = new ArrayList<>(table.getColumns().size());

    for (final FieldInfoImpl<?> field: table.getColumnsImpl()) {
      if (field.isTypeKey() && (ucinfo instanceof UDTTypeClassInfoImpl)) {
        // don't persist type keys for those (only for UDT root entities)
        continue;
      }
      columns.add(field.getColumnName() + " " + field.getDataType().toCQL());
    }
    final StringBuilder builder = new StringBuilder();

    builder.append("CREATE TYPE ");
    if (ifNotExists) {
      builder.append("IF NOT EXISTS ");
    }
    if (getKeyspace() != null) {
      Utils.appendName(builder, getKeyspace()).append(".");
    }
    Utils.appendName(builder, ucinfo.getName());
    builder
      .append(" (")
      .append(StringUtils.join(columns, ","))
      .append(')');
    builder.append(';');
    return new StringBuilder[] { builder };
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.impl.GroupStatementImpl#buildGroupedStatements()
   */
  @Override
  protected final List<StatementImpl<?, ?, ?>> buildGroupedStatements() {
    final ClassInfoImpl<T> cinfo = (ClassInfoImpl<T>)getClassInfo();

    if (cinfo instanceof UDTClassInfoImpl) {
      final StringBuilder[] bs = buildQueryStrings((UDTClassInfoImpl<T>)cinfo);

      if (bs != null) {
        return Arrays.stream(bs)
          .filter(b -> (b != null) && (b.length() != 0))
          .map(b -> init(new SimpleStatementImpl(b.toString(), mgr, bridge)))
          .collect(Collectors.toList());
      }
    }
    return Collections.emptyList();
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.impl.StatementImpl#appendGroupSubType(java.lang.StringBuilder)
   */
  @Override
  protected void appendGroupSubType(StringBuilder builder) {
    builder.append(" CREATE TYPE");
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.CreateType#ifNotExists()
   */
  @Override
  public CreateType<T> ifNotExists() {
    this.ifNotExists = true;
    setDirty();
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.CreateType#where(org.helenus.driver.Clause)
   */
  @Override
  public Where<T> where(Clause clause) {
    return where.and(clause);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.CreateType#where()
   */
  @Override
  public Where<T> where() {
    return where;
  }

  /**
   * The <code>WhereImpl</code> class defines a WHERE clause for the CREATE
   * TYPE statement which can be used to specify keyspace keys used for the
   * keyspace name.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Mar 3, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class WhereImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, CreateTypeImpl<T>>
    implements Where<T> {
    /**
     * Instantiates a new <code>WhereImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the encapsulated statement
     */
    WhereImpl(CreateTypeImpl<T> statement) {
      super(statement);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.CreateType.Where#and(org.helenus.driver.Clause)
     */
    @Override
    public Where<T> and(Clause clause) {
      org.apache.commons.lang3.Validate.notNull(clause, "invalid null clause");
      org.apache.commons.lang3.Validate.isTrue(
        clause instanceof ClauseImpl,
        "unsupported class of clauses: %s",
        clause.getClass().getName()
      );
      org.apache.commons.lang3.Validate.isTrue(
        !(clause instanceof ClauseImpl.DelayedWithObject),
        "unsupported clause '%s' for a CREATE TYPE statement",
        clause
      );
      final ClassInfoImpl<T>.Context context = getContext();
      final ClassInfoImpl<T> cinfo = context.getClassInfo();

      if (clause instanceof ClauseImpl.Delayed) {
        for (final Clause c: ((ClauseImpl.Delayed)clause).processWith(cinfo)) {
          and(c); // recurse to add the processed clause
        }
      } else {
        org.apache.commons.lang3.Validate.isTrue(
          clause instanceof Clause.Equality,
          "unsupported class of clauses: %s",
          clause.getClass().getName()
        );
        final ClauseImpl c = (ClauseImpl)clause;

        if (c instanceof ClauseImpl.CompoundEqClauseImpl) {
          final ClauseImpl.Compound cc = (ClauseImpl.Compound)c;
          final List<String> names = cc.getColumnNames();
          final List<?> values = cc.getColumnValues();

          for (int i = 0; i < names.size(); i++) {
            context.addKeyspaceKey(names.get(i), values.get(i));
          }
        } else {
          context.addKeyspaceKey(c.getColumnName().toString(), c.firstValue());
        }
        setDirty();
      }
      return this;
    }
  }
}
