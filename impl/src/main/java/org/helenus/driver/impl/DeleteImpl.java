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
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;

import com.datastax.driver.core.TypeCodec;

import org.helenus.commons.collections.iterators.CombinationIterator;
import org.helenus.driver.Clause;
import org.helenus.driver.Delete;
import org.helenus.driver.StatementBridge;
import org.helenus.driver.Using;
import org.helenus.driver.VoidFuture;
import org.helenus.driver.codecs.ArgumentsCodec;
import org.helenus.driver.info.ClassInfo;
import org.helenus.driver.info.TableInfo;
import org.helenus.driver.persistence.CQLDataType;
import org.helenus.driver.persistence.Table;

/**
 * The <code>DeleteImpl</code> class extends the functionality of Cassandra's
 * {@link com.datastax.driver.core.querybuilder.Delete} class to provide
 * support for POJOs.
 *
 * @copyright 2015-2016 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Jan 19, 2015 - paouelle - Creation
 *
 * @param <T> The type of POJO associated with this statement.
 *
 * @since 1.0
 */
public class DeleteImpl<T>
  extends StatementImpl<Void, VoidFuture, T>
  implements Delete<T> {
  /**
   * List of tables to delete from.
   *
   * @author paouelle
   */
  private final List<TableInfoImpl<T>> tables = new ArrayList<>(8);

  /**
   * List of columns deleted.
   *
   * @author paouelle
   */
  protected List<Object> columnNames;

  /**
   * Holds the where statement part.
   *
   * @author paouelle
   */
  private final WhereImpl<T> where;

  /**
   * Holds the "USING" options.
   *
   * @author paouelle
   */
  private final OptionsImpl<T> usings;

  /**
   * Holds the conditions for the delete statement.
   *
   * @author paouelle
   */
  private final ConditionsImpl<T> conditions;

  /**
   * Holds a flag indicating if the "IF EXISTS" condition has been selected.
   *
   * @author paouelle
   */
  private boolean ifExists = false;

  /**
   * Flag to keep track of whether or not the special * has been selected.
   *
   * @author paouelle
   */
  protected boolean allSelected = false;

  /**
   * Holds a map of primary key values to use instead of those reported by the
   * POJO context.
   *
   * @author paouelle
   */
  private final Map<String, Object> pkeys_override;

  /**
   * Instantiates a new <code>DeleteImpl</code> object.
   *
   * @author paouelle
   *
   * @param  context the non-<code>null</code> class info context associated
   *         with this statement
   * @param  tables the tables to delete from
   * @param  columnNames the columns names that should be deleted by the query
   * @param  allSelected <code>true</code> if the special COUNT() or *
   *         has been selected
   * @param  mgr the non-<code>null</code> statement manager
   * @param  bridge the non-<code>null</code> statement bridge
   * @throws NullPointerException if <code>context</code> is <code>null</code>
   * @throws IllegalArgumentException if unable to compute the keyspace or table
   *         names based from the given object
   */
  public DeleteImpl(
    ClassInfoImpl<T>.Context context,
    String[] tables,
    List<Object> columnNames,
    boolean allSelected,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    this(context, tables, columnNames, allSelected, null, mgr, bridge);
  }

  /**
   * Instantiates a new <code>DeleteImpl</code> object.
   *
   * @author paouelle
   *
   * @param  context the non-<code>null</code> class info context associated
   *         with this statement
   * @param  tables the tables to delete from
   * @param  columnNames the columns names that should be deleted by the query
   * @param  allSelected <code>true</code> if the special COUNT() or *
   *         has been selected
   * @param  pkeys_override an optional map of primary key values to use instead
   *         of those provided by the POJO context
   * @param  mgr the non-<code>null</code> statement manager
   * @param  bridge the non-<code>null</code> statement bridge
   * @throws NullPointerException if <code>context</code> is <code>null</code>
   * @throws IllegalArgumentException if unable to compute the keyspace or table
   *         names based from the given object
   */
  DeleteImpl(
    ClassInfoImpl<T>.Context context,
    String[] tables,
    List<Object> columnNames,
    boolean allSelected,
    Map<String, Object> pkeys_override,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    super(Void.class, context, mgr, bridge);
    if (tables != null) {
      for (final String table: tables) {
        if (table != null) {
          this.tables.add((TableInfoImpl<T>)context.getClassInfo().getTable(table));
        }
      }
    } else { // fallback to all
      this.tables.addAll(context.getClassInfo().getTablesImpl());
    }
    this.columnNames = columnNames;
    this.allSelected = allSelected;
    org.apache.commons.lang3.Validate.isTrue(
      !(!allSelected && CollectionUtils.isEmpty(columnNames)),
      "must select at least one column"
    );
    this.where = new WhereImpl<>(this);
    this.usings = new OptionsImpl<>(this);
    this.conditions = new ConditionsImpl<>(this);
    this.pkeys_override = pkeys_override;
  }

  /**
   * Instantiates a new <code>DeleteImpl</code> object for all columns.
   *
   * @author paouelle
   *
   * @param  context the non-<code>null</code> class info context associated
   *         with this statement
   * @param  table the table to delete from
   * @param  usings the non-<code>null</code> list of "USINGS" options
   * @param  ifExists <code>true</code> if the "IF EXISTS" condition has been
   *         selected; <code>false</code> otherwise
   * @param  conditions the non-<code>null</code> conditions for the delete
   *         statement
   * @param  pkeys_override an optional map of primary key values to use instead
   *         of those provided by the POJO context
   * @param  mgr the non-<code>null</code> statement manager
   * @param  bridge the non-<code>null</code> statement bridge
   * @throws NullPointerException if <code>context</code> is <code>null</code>
   * @throws IllegalArgumentException if unable to compute the keyspace or table
   *         names based from the given object
   */
  DeleteImpl(
    ClassInfoImpl<T>.Context context,
    TableInfoImpl<T> table,
    List<UsingImpl<?>> usings,
    boolean ifExists,
    List<ClauseImpl> conditions,
    Map<String, Object> pkeys_override,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    super(Void.class, context, mgr, bridge);
    tables.add(table);
    this.columnNames = null;
    this.allSelected = true;
    this.where = new WhereImpl<>(this);
    this.usings = new OptionsImpl<>(this, usings);
    this.ifExists = ifExists;
    this.conditions = new ConditionsImpl<>(this, conditions);
    this.pkeys_override = pkeys_override;
  }

  /**
   * Ands the specified clause along with others while validating them.
   * <p>
   * <i>Note:</i> Clauses referencing columns that are not defined in the
   * specified table are simply ignored.
   *
   * @author paouelle
   *
   * @param  table the non-<code>null</code> table for which to validate or
   *         extract column's values for
   * @param  clauses the non-<code>null</code> list of clauses to which
   *         to add the clause once resolved
   * @param  clause the non-<code>null</code> assignment to and together
   *         with others
   * @throws IllegalArgumentException if clauses reference columns which are
   *         not primary keys or index columns in the POJO for the specified
   *         table or reference invalid values
   */
  private void andClause(
    TableInfoImpl<T> table, List<ClauseImpl> clauses, ClauseImpl clause
  ) {
    // checked if the assignment is a delayed one in which case we need to
    // process it with the POJO and continue with the resulting list of
    // assignments instead of it
    if (clause instanceof ClauseImpl.Delayed) {
      ((ClauseImpl.Delayed)clause).processWith(table)
        .forEach(c -> andClause(table, clauses, c)); // recurse to add the processed clause
    } else if (clause instanceof ClauseImpl.DelayedWithObject) {
      final ClassInfoImpl<T>.POJOContext pctx = getPOJOContext();

      org.apache.commons.lang3.Validate.isTrue(
        pctx != null,
        "unsupported clause '%s' for a DELETE statement",
        clause.getOperation()
      );
      ((ClauseImpl.DelayedWithObject)clause).processWith(table, pctx)
        .forEach(c -> andClause(table, clauses, c)); // recurse to add the processed clause
    } else {
      if (clause instanceof ClauseImpl.Compound) {
        final List<ClauseImpl> eclauses = ((ClauseImpl.Compound)clause).extractSpecialColumns(table);

        if (eclauses != null) {
          eclauses.forEach(c -> andClause(table, clauses, c)); // recurse to add the processed clause
          return;
        } // else - continue with the current one as is
      }
      // only pay attention if the referenced column name(s) is(are) defined in the table
      // as a primary key
      final CharSequence name = clause.getColumnName();

      if (table.hasPrimaryKey(name)) {
        clause.validate(table);
        // the IN relation is only supported for the last column of the partition key
        // or if it is for a multi-key
        if (clause instanceof ClauseImpl.InClauseImpl) {
          org.apache.commons.lang3.Validate.isTrue(
            table.isLastPartitionKey(name)
            || table.isMultiKey(name),
            "'IN' clause is only supported for the last column of the partition key "
            + "or for a multi-key in a DELETE statement: %s",
            name
          );
        } else if (clause instanceof ClauseImpl.CompoundInClauseImpl) {
          for (final String cname: ((ClauseImpl.CompoundInClauseImpl)clause).getColumnNames()) {
            org.apache.commons.lang3.Validate.isTrue(
              table.isLastPartitionKey(cname) || table.isMultiKey(cname),
              "'IN' clause is only supported for the last column of the partition key "
              + "or for a multi-key in a DELETE statement: %s",
              cname
            );
          }
        }
        clauses.add(clause);
      }
    }
  }

  /**
   * Builds a query string for the specified table.
   *
   * @author paouelle
   *
   * @param  table the non-<code>null</code> table for which to build a query
   *         string
   * @param  builders the non-<code>null</code> list of builders where to add
   *         the query strings built
   * @throws IllegalArgumentException if the keyspace has not yet been computed
   *         and cannot be computed with the provided keyspace keys yet or if
   *         assignments reference columns not defined in the POJO or invalid
   *         values or if missing mandatory columns are referenced for the
   *         specified table
   */
  @SuppressWarnings("synthetic-access")
  void buildQueryString(
    TableInfoImpl<T> table, List<StringBuilder> builders
  ) {
    final StringBuilder builder = new StringBuilder();

    builder.append("DELETE ");
    if (columnNames != null) {
      // only pay attention if the referenced column name(s) is(are) defined in the table
      final List<Object> cns = new ArrayList<>(this.columnNames.size());

      for (final Object name: this.columnNames) {
        if (table.hasColumn(name)) {
          // make sure it is not mandatory or a primary key column
          table.validateNotMandatoryOrPrimaryKeyColumn(name);
          cns.add(name);
        }
      }
      if (cns.isEmpty()) {
        return;
      }
      Utils.joinAndAppendNames(
        table, null, mgr.getCodecRegistry(), builder, ",", cns
      );
      builder.append(" ");
    }
    builder.append("FROM ");
    if (getKeyspace() != null) {
      Utils.appendName(builder, getKeyspace()).append(".");
    }
    Utils.appendName(builder, table.getName());
    if (!usings.usings.isEmpty()) {
      builder.append(" USING ");
      Utils.joinAndAppend(
        getKeyspace(), table, null, mgr.getCodecRegistry(), builder, " AND ", usings.usings, null
      );
    }
    // check if the table has multi-keys in which case we need to iterate all
    // possible combinations/values for all keys and generate separate delete
    // statements
    // also check if we have case insensitive keys as we need to convert values
    // to lower case and change the column name being queried
    final Collection<FieldInfoImpl<T>> multiKeys = table.getMultiKeys();
    final Collection<FieldInfoImpl<T>> caseInsensitiveKeys = table.getCaseInsensitiveKeys();

    if (!where.clauses.isEmpty()) {
      // let's first pre-process and validate the clauses for this table
      final List<ClauseImpl> whereClauses = where.getClauses(table);
      final List<ClauseImpl> cs = new ArrayList<>(whereClauses.size()); // preserver order

      for (final ClauseImpl c: whereClauses) {
        andClause(table, cs, c);
      }
      if (cs.isEmpty()) { // nothing to select for this delete so skip
        return;
      }
      builder.append(" WHERE ");
      if (!caseInsensitiveKeys.isEmpty() || !multiKeys.isEmpty()) {
        // prepare all sets of values for all multi-keys present in the clause
        // and properly convert all case insensitive keys
        final List<Collection<ClauseImpl.EqClauseImpl>> sets = new ArrayList<>(multiKeys.size());

        for (final ListIterator<ClauseImpl> i = cs.listIterator(); i.hasNext(); ) {
          final ClauseImpl c = i.next();

          if (c instanceof ClauseImpl.SimpleClauseImpl) {
            // if we have case insensitive keys then we need to process those first
            // and then address the multi keys combinations
            final String name = c.getColumnName().toString();
            final FieldInfoImpl<T> finfo = table.getColumnImpl(name);

            if (finfo != null) {
              final boolean ci = finfo.isCaseInsensitiveKey();

              if (finfo.isMultiKey()) {
                // remove the clause from the list as we will deal with a set of multi-keys
                i.remove();
                final List<ClauseImpl.EqClauseImpl> set = new ArrayList<>();
                final String mkname = StatementImpl.MK_PREFIX + name;

                for (final Object v: c.values()) {
                  if (v instanceof Collection<?>) {
                    for (final Object sv: (Collection<?>)v) {
                      if (ci && (sv != null)) {
                        set.add(new ClauseImpl.EqClauseImpl(
                          mkname, StringUtils.lowerCase(sv.toString())
                        ));
                      } else {
                        set.add(new ClauseImpl.EqClauseImpl(mkname, sv));
                      }
                    }
                  } else if (ci && (v != null)) {
                    set.add(new ClauseImpl.EqClauseImpl(
                      mkname, StringUtils.lowerCase(v.toString())
                    ));
                  } else {
                    set.add(new ClauseImpl.EqClauseImpl(mkname, v));
                  }
                }
                if (!set.isEmpty()) {
                  sets.add(set);
                }
              } else if (ci) {
                final Object v = c.firstValue();

                i.set(new ClauseImpl.SimpleClauseImpl(
                  StatementImpl.CI_PREFIX + name,
                  c.getOperation(),
                  (v != null) ? StringUtils.lowerCase(v.toString()) : null
                ));
              }
            }
          } else if (c instanceof ClauseImpl.InClauseImpl) {
            final String name = c.getColumnName().toString();
            final FieldInfoImpl<T> finfo = table.getColumnImpl(name);

            // IN are not supported for multi-keys, so only need to handle case insensitive!
            if ((finfo != null) && finfo.isCaseInsensitiveKey()) {
              i.set(new ClauseImpl.InClauseImpl(
                StatementImpl.CI_PREFIX + name,
                c.values().stream()
                  .map(v -> (v != null) ? StringUtils.lowerCase(v.toString()) : null)
                  .collect(Collectors.toCollection(LinkedHashSet::new))
              ));
            }
          }
        }
        if (!sets.isEmpty()) {
          // now iterate all combination of these sets to generate delete statements
          // for each combination
          @SuppressWarnings("unchecked")
          final Collection<ClauseImpl.EqClauseImpl>[] asets = new Collection[sets.size()];

          for (final Iterator<List<ClauseImpl.EqClauseImpl>> i = new CombinationIterator<>(ClauseImpl.EqClauseImpl.class, sets.toArray(asets)); i.hasNext(); ) {
            final StringBuilder sb = new StringBuilder(builder);

            // add the multi-key clause values from this combination to the list of clauses
            Utils.joinAndAppend(
              getKeyspace(), table, null, mgr.getCodecRegistry(), sb, " AND ", i.next(), cs, null
            );
            builders.add(finishBuildingQueryString(table, sb));
          }
          return;
        }
      }
      // we didn't have any multi-keys in the clauses so just delete it based
      // on the given clauses
      Utils.joinAndAppend(
        getKeyspace(), table, null, mgr.getCodecRegistry(), builder, " AND ", cs, null
      );
    } else { // no clauses provided, so add where clauses for all primary key columns
      try {
        final Map<String, Triple<Object, CQLDataType, TypeCodec<?>>> pkeys;

        try {
          if (pkeys_override == null) {
            pkeys = getPOJOContext().getPrimaryKeyColumnValues(table.getName());
          } else {
            pkeys = getPOJOContext().getPrimaryKeyColumnValues(table.getName(), pkeys_override);
          }
        } catch (EmptyOptionalPrimaryKeyException e) {
          // ignore and continue without updating this table
          return;
        }
        if (!pkeys.isEmpty()) {
          builder.append(" WHERE ");
          // if we have case insensitive keys then we need to process those first
          // and then address the multi keys combinations
          if (!caseInsensitiveKeys.isEmpty()) {
            for (final FieldInfoImpl<T> finfo: caseInsensitiveKeys) {
              if (finfo.isMultiKey()) { // will be handled separately after this
                continue;
              }
              final String name = finfo.getColumnName();
              final Triple<Object, CQLDataType, TypeCodec<?>> pset = pkeys.remove(name);

              if (pset != null) {
                final Object v = pset.getLeft();

                pkeys.put(
                  StatementImpl.CI_PREFIX + name,
                  Triple.of(
                    (v != null) ? StringUtils.lowerCase(v.toString()) : null,
                    pset.getMiddle(),
                    pset.getRight()
                  )
                );
              }
            }
          }
          if (!multiKeys.isEmpty()) {
            // prepare all sets of values for all multi-keys present in the clause
            final List<FieldInfoImpl<T>> cfinfos = new ArrayList<>(multiKeys.size());
            final List<Collection<Object>> sets = new ArrayList<>(multiKeys.size());

            for (final FieldInfoImpl<T> finfo: multiKeys) {
              final Triple<Object, CQLDataType, TypeCodec<?>> pset = pkeys.remove(finfo.getColumnName());

              if (pset != null) {
                @SuppressWarnings("unchecked")
                final Collection<Object> set = (Collection<Object>)pset.getLeft();

                if (set != null) { // we have keys for this multi-key column
                  cfinfos.add(finfo);
                  if (finfo.isCaseInsensitiveKey()) {
                    sets.add(set.stream()
                      .map(v -> (v != null) ? StringUtils.lowerCase(v.toString()) : null)
                      .collect(Collectors.toCollection(LinkedHashSet::new))
                    );
                  } else {
                    sets.add(set);
                  }
                }
              }
            }
            if (!sets.isEmpty()) {
              // now iterate all combination of these sets to generate delete statements
              // for each combination
              @SuppressWarnings("unchecked")
              final Collection<Object>[] asets = new Collection[sets.size()];

              for (final Iterator<List<Object>> i = new CombinationIterator<>(Object.class, sets.toArray(asets)); i.hasNext(); ) {
                // add the multi-key clause values from this combination to the map of primary keys
                int j = -1;
                for (final Object k: i.next()) {
                  final FieldInfoImpl<T> finfo = cfinfos.get(++j);

                  pkeys.put(
                    StatementImpl.MK_PREFIX + finfo.getColumnName(),
                    Triple.of(k, finfo.getDataType().getElementType(), ((ArgumentsCodec<?>)finfo.getCodec(getKeyspace())).codec(0))
                  );
                }
                final StringBuilder sb = new StringBuilder(builder);

                Utils.joinAndAppendNamesAndValues(null, mgr.getCodecRegistry(), sb, " AND ", "=", pkeys, null);
                builders.add(finishBuildingQueryString(table, sb));
              }
              return;
            }
          }
          // we didn't have any multi-keys in the list (unlikely) so just delete it
          // based on the provided list
          Utils.joinAndAppendNamesAndValues(null, mgr.getCodecRegistry(), builder, " AND ", "=", pkeys, null);
        }
      } catch (EmptyOptionalPrimaryKeyException e) {
        // ignore and continue without updating this table
        return;
      }
    }
    builders.add(finishBuildingQueryString(table, builder));
  }

  /**
   * Finishes building a query string for the specified table.
   *
   * @author paouelle
   *
   * @param  table the non-<code>null</code> table for which to build a query
   *         string
   * @param  builder the non-<code>null</code> builder where to add the rest of
   *         the query string to build
   * @return <code>builder</code>
   */
  @SuppressWarnings("synthetic-access")
  private StringBuilder finishBuildingQueryString(
    TableInfoImpl<T> table, StringBuilder builder
  ) {
    if (ifExists) {
      builder.append(" IF EXISTS");
    } else if (!conditions.conditions.isEmpty()) {
      // TODO: we need to also filter based on this table as there might not be any condition to set
      // let's first validate the condition for this table
      for (final ClauseImpl c: conditions.conditions) {
        c.validate(table);
      }
      builder.append(" IF ");
      Utils.joinAndAppend(
        getKeyspace(), table, null, mgr.getCodecRegistry(), builder, " AND ", conditions.conditions, null
      );
    }
    return builder;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.impl.StatementImpl#simpleSize()
   */
  @Override
  protected int simpleSize() {
    if (super.simpleSize == -1) {
      if (!isEnabled()) {
        super.simpleSize = 0;
      } else {
        super.simpleSize = tables.size();
      }
    }
    return super.simpleSize;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.impl.StatementImpl#buildQueryStrings()
   */
  @SuppressWarnings("synthetic-access")
  @Override
  protected StringBuilder[] buildQueryStrings() {
    if (!isEnabled()) {
      return null;
    }
    final List<StringBuilder> builders = new ArrayList<>(tables.size());
    InsertImpl<T> insert = null;

    for (final TableInfoImpl<T> table: tables) {
      if ((table.getTable().type() == Table.Type.AUDIT)
          || (table.getTable().type() == Table.Type.NO_DELETE)) {
        // deal with AUDIT and NO_DELETE tables only if we were deleting all from the POJO
        // with no clauses
        // otherwise leave it to the statements to deal with it
        if ((columnNames == null) && where.clauses.isEmpty()) {
          // we must create an insert for this table if not already done
          // otherwise, simply add this table to the list of tables to handle
          if (insert == null) {
            insert = init(new InsertImpl<>(getPOJOContext(), table, usings.usings, mgr, bridge));
          } else { // add this table to the mix
            insert.into(table);
          }
          continue;
        } // else - fall-through to handle it normally
      } // else - STANDARD table is handled normally
      buildQueryString(table, builders);
    }
    if (insert != null) {
      insert.buildQueryStrings(builders);
    }
    if (builders.isEmpty()) {
      return null;
    }
    return builders.toArray(new StringBuilder[builders.size()]);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.impl.StatementImpl#appendGroupType(java.lang.StringBuilder)
   */
  @Override
  protected void appendGroupType(StringBuilder builder) {
    builder.append("BATCH");
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#tables()
   */
  @Override
  public Stream<TableInfo<T>> tables() {
    return tables.stream().map(t -> t);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Delete#where(org.helenus.driver.Clause)
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
   * @see org.helenus.driver.Delete#where()
   */
  @Override
  public Where<T> where() {
    return where;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Delete#using(org.helenus.driver.Using)
   */
  @Override
  public Options<T> using(Using<?> using) {
    return usings.and(using);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Insert#usings()
   */
  @SuppressWarnings({"rawtypes", "cast", "unchecked", "synthetic-access"})
  @Override
  public Stream<Using<?>> usings() {
    return (Stream<Using<?>>)(Stream)usings.usings.stream();
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Insert#getUsing(java.lang.String)
   */
  @SuppressWarnings({"rawtypes", "cast", "unchecked"})
  @Override
  public <U> Optional<Using<U>> getUsing(String name) {
    return (Optional<Using<U>>)(Optional)usings()
      .filter(u -> u.getName().equals(name)).findAny();
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Delete#ifExists()
   */
  @SuppressWarnings("synthetic-access")
  @Override
  public Delete<T> ifExists() {
    org.apache.commons.lang3.Validate.isTrue(
      conditions.conditions.isEmpty(),
      "cannot combined additional conditions with IF EXISTS"
    );
    if (!ifExists) {
      setDirty();
      this.ifExists = true;
    }
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Delete#onlyIf(org.helenus.driver.Clause)
   */
  @Override
  public Conditions<T> onlyIf(Clause condition) {
    return conditions.and(condition);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Delete#onlyIf()
   */
  @Override
  public Conditions<T> onlyIf() {
    org.apache.commons.lang3.Validate.isTrue(
      !ifExists, "cannot combined additional conditions with IF EXISTS"
    );
    return conditions;
  }

  /**
   * The <code>WhereImpl</code> class defines WHERE clause for a DELETE
   * statement.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Jan 19, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class WhereImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, DeleteImpl<T>>
    implements Where<T> {
    /**
     * Holds the list of clauses for this statement
     *
     * @author paouelle
     */
    private final List<ClauseImpl> clauses = new ArrayList<>(10);

    /**
     * Instantiates a new <code>WhereImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement to which this
     *        "WHERE" part belongs to
     */
    WhereImpl(DeleteImpl<T> statement) {
      super(statement);
    }

    /**
     * Gets the "where" clauses while adding missing final partition keys.
     *
     * @author paouelle
     *
     * @param  table the table for which to get the "where" clauses
     * @return the "where" clauses
     */
    private List<ClauseImpl> getClauses(TableInfoImpl<T> table) {
      // check if the table defines any final primary keys
      // in which case we want to make sure to add clauses for them too
      final List<ClauseImpl> clauses = new ArrayList<>(this.clauses);

      for (final Map.Entry<String, Object> e: table.getFinalPrimaryKeyValues().entrySet()) {
        final String name = e.getKey();

        // check if we don't already have a clause for that column and add it if not
        if (!this.clauses.stream().anyMatch(c -> c.containsColumn(name))) {
          clauses.add(new ClauseImpl.EqClauseImpl(name, e.getValue()));
        }
      }
      return clauses;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Where#and(org.helenus.driver.Clause)
     */
    @Override
    public Where<T> and(Clause clause) {
      org.apache.commons.lang3.Validate.notNull(clause, "invalid null clause");
      org.apache.commons.lang3.Validate.isTrue(
        clause instanceof ClauseImpl,
        "unsupported class of clauses: %s",
        clause.getClass().getName()
      );
      final ClauseImpl c = (ClauseImpl)clause;

      if (!(c instanceof ClauseImpl.Delayed)
          && !(c instanceof ClauseImpl.DelayedWithObject)) {
        final ClassInfoImpl<T>.Context context = getContext();
        final ClassInfoImpl<T> cinfo = context.getClassInfo();

        // pre-validate against any table
        if (c instanceof ClauseImpl.Compound) {
          final ClauseImpl.Compound cc = (ClauseImpl.Compound)c;
          final List<String> names = cc.getColumnNames();
          final List<?> values = cc.getColumnValues();

          if (c instanceof ClauseImpl.CompoundEqClauseImpl) {
            for (int i = 0; i < names.size(); i++) {
              final String name = names.get(i);

              cinfo.validateColumnOrKeyspaceKey(name);
              if (cinfo.isKeyspaceKey(name)) {
                context.addKeyspaceKey(name, values.get(i));
              }
            }
          } else {
            for (final String name: names) {
              cinfo.validateColumn(name);
            }
          }
        } else {
          final String name = c.getColumnName().toString();

          if (c instanceof ClauseImpl.EqClauseImpl) {
            cinfo.validateColumnOrKeyspaceKey(name);
            if (cinfo.isKeyspaceKey(name)) {
              context.addKeyspaceKey(name, c.firstValue());
            }
          } else {
            cinfo.validateColumn(name);
          }
        }
      }
      clauses.add(c);
      setDirty();
      return this;
    }

    /**
     * Adds an option to the DELETE statement this WHERE clause is part of.
     *
     * @author paouelle
     *
     * @param  using the using clause to add.
     * @return the options of the DELETE statement this WHERE clause is part of.
     */
    @Override
    public Options<T> using(Using<?> using) {
      return statement.using(using);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Where#ifExists()
     */
    @Override
    public Delete<T> ifExists() {
      return statement.ifExists();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Where#onlyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> onlyIf(Clause condition) {
      return statement.onlyIf(condition);
    }
  }

  /**
   * The <code>OptionsImpl</code> class defines the options of a DELETE statement.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Jan 19, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class OptionsImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, DeleteImpl<T>>
    implements Options<T> {
    /**
     * Holds the list of "USINGS" options.
     *
     * @author paouelle
     */
    private final List<UsingImpl<?>> usings;

    /**
     * Instantiates a new <code>OptionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating options
     */
    OptionsImpl(DeleteImpl<T> statement) {
      this(statement, new ArrayList<>(5));
    }

    /**
     * Instantiates a new <code>OptionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating options
     * @param usings the non-<code>null</code> list of "USINGS" options
     */
    OptionsImpl(DeleteImpl<T> statement, List<UsingImpl<?>> usings) {
      super(statement);
      this.usings = usings;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Options#and(org.helenus.driver.Using)
     */
    @Override
    public Options<T> and(Using<?> using) {
      org.apache.commons.lang3.Validate.notNull(using, "invalid null using");
      org.apache.commons.lang3.Validate.isTrue(
        using instanceof UsingImpl,
        "unsupported class of usings: %s",
        using.getClass().getName()
      );
      usings.add(((UsingImpl<?>)using).setStatement(statement));
      setDirty();
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Options#where(org.helenus.driver.Clause)
     */
    @Override
    public Where<T> where(Clause clause) {
      return statement.where(clause);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Options#ifExists()
     */
    @Override
    public Delete<T> ifExists() {
      return statement.ifExists();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Options#onlyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> onlyIf(Clause condition) {
      return statement.onlyIf(condition);
    }
  }

  /**
   * The <code>BuilderImpl</code> class defines an in-construction DELETE statement.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Jan 19, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class BuilderImpl<T> implements Builder<T> {
    /**
     * Holds the statement manager.
     *
     * @author paouelle
     */
    protected final StatementManagerImpl mgr;

    /**
     * Holds the statement bridge.
     *
     * @author paouelle
     */
    protected final StatementBridge bridge;

    /**
     * Holds the context associated with the POJO class for this statement.
     *
     * @author paouelle
     */
    protected final ClassInfoImpl<T>.Context context;

    /**
     * List of columns added.
     *
     * @author paouelle
     */
    protected List<Object> columnNames;

    /**
     * Flag to keep track of whether or not * has been selected.
     *
     * @author paouelle
     */
    protected boolean allSelected = false;

    /**
     * Instantiates a new <code>BuilderImpl</code> object.
     *
     * @author paouelle
     *
     * @param context the non-<code>null</code> class info context
     *        associated with this statement
     * @param mgr the non-<code>null</code> statement manager
     * @param bridge the non-<code>null</code> statement bridge
     */
    BuilderImpl(
      ClassInfoImpl<T>.Context context,
      StatementManagerImpl mgr,
      StatementBridge bridge
    ) {
      this.mgr = mgr;
      this.bridge = bridge;
      this.context = context;
    }

    /**
     * Instantiates a new <code>BuilderImpl</code> object.
     *
     * @author paouelle
     *
     * @param  context the non-<code>null</code> class info context
     *         associated with this statement
     * @param  columnNames the column names
     * @param  mgr the non-<code>null</code> statement manager
     * @param  bridge the non-<code>null</code> statement bridge
     * @throws NullPointerException if <code>context</code> is <code>null</code>
     * @throws IllegalArgumentException if any of the specified columns are not
     *         defined by the POJO
     */
    BuilderImpl(
      ClassInfoImpl<T>.Context context,
      List<Object> columnNames,
      StatementManagerImpl mgr,
      StatementBridge bridge
    ) {
      this(context, mgr, bridge);
      context.getClassInfo().validateColumns(columnNames);
      this.columnNames = columnNames;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.CreateIndex.Builder#getObjectClass()
     */
    @Override
    public Class<T> getObjectClass() {
      return context.getObjectClass();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.CreateIndex.Builder#getClassInfo()
     */
    @Override
    public ClassInfo<T> getClassInfo() {
      return context.getClassInfo();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Builder#from(java.lang.String[])
     */
    @Override
    public Delete<T> from(String... tables) {
      if (columnNames == null) {
        allSelected = true;
      }
      return new DeleteImpl<>(context, tables, columnNames, allSelected, mgr, bridge);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Builder#from(java.util.stream.Stream)
     */
    @Override
    public Delete<T> from(Stream<String> tables) {
      if (columnNames == null) {
        allSelected = true;
      }
      return new DeleteImpl<>(
        context, tables.toArray(String[]::new), columnNames, allSelected, mgr, bridge
      );
    }

    /**
     * Specifies to delete from all tables defined in the POJO using the
     * keyspace defined in the POJO.
     * <p>
     * This flavor should be used when the POJO doesn't require keyspace keys to the
     * keyspace name.
     *
     * @author paouelle
     *
     * @return a newly build DELETE statement that deletes from all tables
     *         defined in the POJO
     * @throws IllegalArgumentException if unable to compute the keyspace name
     *         without any keyspace keys or any of the referenced columns are not
     *         defined by the POJO
     */
    @Override
    public Delete<T> fromAll() {
      if (columnNames == null) {
        allSelected = true;
      }
      return new DeleteImpl<>(context, null, columnNames, allSelected, mgr, bridge);
    }
  }

  /**
   * The <code>SelectionImpl</code> class defines a selection clause for an
   * in-construction DELETE statement.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Jan 19, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the response from this statement.
   *
   * @since 1.0
   */
  public static class SelectionImpl<T>
    extends BuilderImpl<T>
    implements Selection<T> {
    /**
     * Instantiates a new <code>SelectionImpl</code> object.
     *
     * @author paouelle
     *
     * @param context the non-<code>null</code> class info context
     *        associated with this statement
     * @param mgr the non-<code>null</code> statement manager
     * @param bridge the non-<code>null</code> statement bridge
     */
    SelectionImpl(
      ClassInfoImpl<T>.Context context,
      StatementManagerImpl mgr,
      StatementBridge bridge
    ) {
      super(context, mgr, bridge);
    }

    /**
     * Adds the specified column name.
     *
     * @author paouelle
     *
     * @param  name the non-<code>null</code> column name to be added
     * @return this for chaining
     * @throws NullPointerException if <code>name</code> is <code>null</code>
     * @throws IllegalArgumentException if the specified column is not defined
     *         by the POJO
     */
    private Selection<T> addName(Object name) {
      context.getClassInfo().validateColumn(name);
      if (columnNames == null) {
        super.columnNames = new ArrayList<>(25);
      }
      columnNames.add(name);
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Selection#all()
     */
    @Override
    public Builder<T> all() {
      org.apache.commons.lang3.Validate.validState(
        columnNames == null,
        "some columns (%s) have already been selected",
        StringUtils.join(columnNames, ", ")
      );
      super.allSelected = true;
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Selection#column(java.lang.String)
     */
    @Override
    public Selection<T> column(String name) {
      return addName(name);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Selection#listElt(java.lang.String, int)
     */
    @Override
    public Selection<T> listElt(String columnName, int idx) {
      return addName(new Utils.CNameIndex(columnName, idx));
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Selection#mapElt(java.lang.String, java.lang.Object)
     */
    @Override
    public Selection<T> mapElt(String columnName, Object key) {
      return addName(new Utils.CNameKey(columnName, key));
    }
  }

  /**
   * Conditions for a DELETE statement.
   * <p>
   * When provided some conditions, an update will not apply unless the provided
   * conditions applies.
   * <p>
   * Please keep in mind that provided conditions has a non negligible
   * performance impact and should be avoided when possible.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Aug 5, 2016 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class ConditionsImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, DeleteImpl<T>>
    implements Conditions<T> {
    /**
     * Holds the list of conditions for the statement
     *
     * @author paouelle
     */
    private final List<ClauseImpl> conditions;

    /**
     * Instantiates a new <code>ConditionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating conditions
     */
    ConditionsImpl(DeleteImpl<T> statement) {
      this(statement, new ArrayList<>(10));
    }

    /**
     * Instantiates a new <code>ConditionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating conditions
     * @param conditions the non-<code>null</code> conditions for the statement
     */
    ConditionsImpl(DeleteImpl<T> statement, List<ClauseImpl> conditions) {
      super(statement);
      this.conditions = conditions;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Conditions#and(org.helenus.driver.Clause)
     */
    @SuppressWarnings("synthetic-access")
    @Override
    public Conditions<T> and(Clause condition) {
      org.apache.commons.lang3.Validate.notNull(condition, "invalid null condition");
      org.apache.commons.lang3.Validate.isTrue(
        !statement.ifExists,
        "cannot combined additional conditions with IF EXISTS"
      );
      org.apache.commons.lang3.Validate.isTrue(
        condition instanceof ClauseImpl,
        "unsupported class of clauses: %s",
        condition.getClass().getName()
      );
      final ClauseImpl c = (ClauseImpl)condition;

      // just to be safe, validate anyway
      org.apache.commons.lang3.Validate.isTrue("=".equals(c.getOperation()), "unsupported condition: %s", c);
      final ClassInfoImpl<T> cinfo = getPOJOContext().getClassInfo();

      // pre-validate against any table
      if (c instanceof ClauseImpl.Compound) {
        ((ClauseImpl.Compound)c).getColumnNames()
          .forEach(name -> cinfo.validateColumn(name));
      } else {
        cinfo.validateColumn(c.getColumnName().toString());
      }
      conditions.add(c);
      setDirty();
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Conditions#where(org.helenus.driver.Clause)
     */
    @Override
    public Where<T> where(Clause clause) {
      return statement.where(clause);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Delete.Conditions#using(org.helenus.driver.Using)
     */
    @Override
    public Options<T> using(Using<?> using) {
      return statement.using(using);
    }
  }
}
