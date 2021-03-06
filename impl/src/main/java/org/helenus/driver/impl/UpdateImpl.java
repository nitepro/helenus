/*
 * Copyright (C) 2015-2017 The Helenus Driver Project Authors.
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.TypeCodec;

import org.helenus.commons.collections.iterators.CombinationIterator;
import org.helenus.driver.Assignment;
import org.helenus.driver.Clause;
import org.helenus.driver.StatementBridge;
import org.helenus.driver.StatementBuilder;
import org.helenus.driver.Update;
import org.helenus.driver.UpdateNotAppliedException;
import org.helenus.driver.Using;
import org.helenus.driver.VoidFuture;
import org.helenus.driver.codecs.ArgumentsCodec;
import org.helenus.driver.impl.AssignmentImpl.CounterAssignmentImpl;
import org.helenus.driver.info.TableInfo;
import org.helenus.driver.persistence.CQLDataType;
import org.helenus.driver.persistence.Table;

/**
 * The <code>UpdateImpl</code> class extends the functionality of Cassandra's
 * {@link com.datastax.driver.core.querybuilder.Update} class to provide support
 * for POJOs.
 *
 * @copyright 2015-20176 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Jan 19, 2015 - paouelle - Creation
 *
 * @param <T> The type of POJO associated with this statement.
 *
 * @since 1.0
 */
public class UpdateImpl<T>
  extends StatementImpl<Void, VoidFuture, T>
  implements Update<T> {
  /**
   * List of tables to be updated.
   *
   * @author paouelle
   */
  private final List<TableInfoImpl<T>> tables = new ArrayList<>(8);

  /**
   * Holds the assignments for this statement.
   *
   * @author paouelle
   */
  private final AssignmentsImpl<T> assignments;

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
   * Holds the conditions for the update statement.
   *
   * @author paouelle
   */
  private final ConditionsImpl<T> conditions;

  /**
   * Holds the previous conditions for the update statement.
   *
   * @author paouelle
   */
  private final ConditionsImpl<T> previousConditions;

  /**
   * Flag indicating if the "IF EXISTS" option has been selected.
   *
   * @author paouelle
   */
  private volatile boolean ifExists;

  /**
   * Instantiates a new <code>UpdateImpl</code> object.
   *
   * @author paouelle
   *
   * @param  context the non-<code>null</code> class info context for the POJO
   *         associated with this statement
   * @param  mgr the non-<code>null</code> statement manager
   * @param  bridge the non-<code>null</code> statement bridge
   * @throws NullPointerException if <code>context</code> is <code>null</code>
   * @throws IllegalArgumentException if unable to compute the keyspace or table
   *         names based from the given object
   */
  public UpdateImpl(
    ClassInfoImpl<T>.POJOContext context,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    this(context, null, mgr, bridge);
  }

  /**
   * Instantiates a new <code>UpdateImpl</code> object.
   *
   * @author paouelle
   *
   * @param  context the non-<code>null</code> class info context for the POJO
   *         associated with this statement
   * @param  tables the tables to update
   * @param  mgr the non-<code>null</code> statement manager
   * @param  bridge the non-<code>null</code> statement bridge
   * @throws NullPointerException if <code>context</code> is <code>null</code>
   * @throws IllegalArgumentException if any of the specified tables are not
   *         defined in the POJO
   */
  public UpdateImpl(
    ClassInfoImpl<T>.POJOContext context,
    String[] tables,
    StatementManagerImpl mgr,
    StatementBridge bridge
  ) {
    super(Void.class, context, mgr, bridge);
    if (tables != null) {
      for (final String table: tables) {
        if (table != null) {
          this.tables.add((TableInfoImpl<T>)context.getClassInfo().getTable(table)); // will throw IAE
        } // else - skip
      }
    } else { // fallback to all
      this.tables.addAll(context.getClassInfo().getTablesImpl());
    }
    this.assignments = new AssignmentsImpl<>(this);
    this.where = new WhereImpl<>(this);
    this.usings = new OptionsImpl<>(this);
    this.conditions = new ConditionsImpl<>(this);
    this.previousConditions = new ConditionsImpl<>(this);
  }

  /**
   * Ands the specified assignment along with others while validating them.
   * <p>
   * <i>Note:</i> Assignments referencing columns that are not defined in the
   * specified table are simply ignored.
   *
   * @author paouelle
   *
   * @param  table the non-<code>null</code> table for which to validate or
   *         extract column's values for
   * @param  assignments the non-<code>null</code> list of assignments to which
   *         to and the assignment
   * @param  assignment the non-<code>null</code> assignment to and together
   *         with others
   * @throws IllegalArgumentException if assignments reference invalid values
   *         or if missing mandatory columns are referenced for the specified
   *         table
   */
  private void andAssignment(
    TableInfoImpl<T> table,
    List<AssignmentImpl> assignments,
    AssignmentImpl assignment
  ) {
    // checked if the assignment is a delayed one in which case we need to
    // process it with the POJO and continue with the resulting list of
    // assignments instead of it
    if (assignment instanceof AssignmentImpl.DelayedWithObject) {
      for (final AssignmentImpl a: ((AssignmentImpl.DelayedWithObject)assignment).processWith(table, getPOJOContext())) {
        andAssignment(table, assignments, a); // recurse to add the processed assignment
      }
    } else {
      // only pay attention if the referenced column name is defined in the table
      if (table.hasColumn(assignment.getColumnName())) {
        try {
          assignment.validate(table);
        } catch (EmptyOptionalPrimaryKeyException e) {
          if (assignment instanceof AssignmentImpl.ReplaceAssignmentImpl) {
            // special case for replace assignments as we will need
            // to potentially delete the old row if old was not null or add
            // only a new row if old was null but not new
            // so fall through in all cases and let the update take care of it
          } else {
            throw e;
          }
        }
        assignments.add(assignment);
      }
    }
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
   *         to and the clause once resolved
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
  private void buildQueryStrings(
    TableInfoImpl<T> table, List<StringBuilder> builders
  ) {
    final StringBuilder builder = new StringBuilder();

    builder.append("UPDATE ");
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
    final Collection<FieldInfoImpl<T>> multiKeys = table.getMultiKeys();
    final Collection<FieldInfoImpl<T>> caseInsensitiveKeys = table.getCaseInsensitiveKeys();
    AssignmentsImpl<T> assignments = this.assignments;

    if (assignments.isEmpty()) {
      // if it is empty then fallback to all non-primary columns
      assignments = (AssignmentsImpl<T>)new AssignmentsImpl<>(this).and(
        new AssignmentImpl.DelayedSetAllAssignmentImpl()
      );
      if (!caseInsensitiveKeys.isEmpty()) {
        // we need to add the key set for each case insensitive keys in the assignments
        // as it would not have been added by the DelayedSetAll since it is marked
        // as a key. However, in this context the real key is
        // the special "ci_<name>" one and not the field which is annotated as one
        // make sure not to report we are adding them all from that point on since
        // we are about to add more delayed set for each case insensitive keys
        assignments.hasAllFromObject = false;
        for (final FieldInfoImpl<T> finfo: caseInsensitiveKeys) {
          if (finfo.isMultiKey()) { // will be handled separately after this
            continue;
          }
          final String cname = finfo.getColumnName();
          final AssignmentImpl.WithOldValue old = this.assignments.previous.get(cname);

          if (old != null) {
            assignments.assignments.add(
              new AssignmentImpl.DelayedReplaceAssignmentImpl(cname, old.getOldValue())
            );
          } else {
            assignments.assignments.add(
              new AssignmentImpl.DelayedSetAssignmentImpl(cname)
            );
          }
        }
      }
      if (!multiKeys.isEmpty()) {
        // we need to add the key set for each multi-keys in the assignments as
        // it would not have been added by the DelayedSetAll since it is marked
        // as a key. However, in this context the real key is
        // the special "mk_<name>" one and not the field which is annotated as one
        // make sure not to report we are adding them all from that point on since
        // we are about to add more delayed set for each multi keys
        assignments.hasAllFromObject = false;
        for (final FieldInfoImpl<T> finfo: multiKeys) {
          final String cname = finfo.getColumnName();
          final AssignmentImpl.WithOldValue old = this.assignments.previous.get(cname);

          if (old != null) {
            assignments.assignments.add(
              new AssignmentImpl.DelayedReplaceAssignmentImpl(cname, old.getOldValue())
            );
          } else {
            assignments.assignments.add(
              new AssignmentImpl.DelayedSetAssignmentImpl(cname)
            );
          }
        }
      }
      // we need to add the key set for each old values in the assignments as
      // it would not have been added by the DelayedSetAll since it is marked as
      // a key. We can skip multi-keys and case insensitive keys as they would be added above.
      // make sure not to report we are adding them all from that point on since
      // we are about to add more delayed set for each old key values
      for (final AssignmentImpl.WithOldValue a: this.assignments.previous.values()) {
        final CharSequence cname = ((AssignmentImpl)a).getColumnName();

        if (table.getPrimaryKey(cname) != null) { // old value for a primary key
          if ((multiKeys.isEmpty() || !table.isMultiKey(cname))
              && (caseInsensitiveKeys.isEmpty() || !table.isCaseInsensitiveKey(cname))) {
            assignments.hasAllFromObject = false;
            assignments.assignments.add(
              new AssignmentImpl.DelayedReplaceAssignmentImpl(cname, a.getOldValue())
            );
          } // else - already handled above
        }
      }
    }
    if (!assignments.assignments.isEmpty()) {
      // let's first preprocess and validate the assignments for this table
      final List<AssignmentImpl> as = new ArrayList<>(assignments.assignments.size());

      for (final AssignmentImpl a: assignments.assignments) {
        if (assignments.hasAllFromObject()) {
          if (a instanceof AssignmentImpl.DelayedSetAssignmentImpl) {
            if (((AssignmentImpl.DelayedSetAssignmentImpl)a).object == null) {
              // skip it as we are already adding all from the object anyway
              continue;
            }
          }
        }
        andAssignment(table, as, a);
      }
      if (as.isEmpty()) { // nothing to set for this table so skip
        return;
      }
      for (final AssignmentImpl.WithOldValue a: this.assignments.previous.values()) {
        try {
          ((AssignmentImpl)a).validate(table);
        } catch (EmptyOptionalPrimaryKeyException e) {
          // special case as we will need to potentially delete the old row if
          // old was not null or add only a new row if old was null but not new
        }
      }
      // if we detect that a primary key is part of the SET, then we can only
      // do a full INSERT of the whole POJO as we are actually creating a whole
      // new row in the table. It will be up to the user to delete any old one
      // as we have no clue what they were before since the POJO only keep track
      // of the new ones, however, we will verify if the value has changed
      // compared to the one reported by the object; if it has then we will first
      // generate a delete and then follow by the full insert
      boolean foundPK = false;
      Map<String, Object> old_values = null;

      for (final AssignmentImpl a: as) {
        if (table.getPrimaryKey(a.getColumnName()) != null) { // assigning to a primary key
          foundPK = true;
          final AssignmentImpl.WithOldValue oa = this.assignments.previous.get(a.getColumnName().toString());

          if (oa != null) { // great, we know what was the old one!
            if (old_values == null) {
              old_values = new LinkedHashMap<>(table.getPrimaryKeys().size());
            }
            Object oldval = oa.getOldValue();

            if (!caseInsensitiveKeys.isEmpty()) {
              if (a instanceof AssignmentImpl.SetAssignmentImpl) {
                if (oldval != null) {
                  final FieldInfoImpl<?> f = table.getColumnImpl(a.getColumnName());

                  if (f.isCaseInsensitiveKey() && !f.isMultiKey()) { // multi-keys are handled next
                    // lower case the old value
                    oldval = StringUtils.lowerCase(oldval.toString());
                  }
                }
              }
            }
            if (!multiKeys.isEmpty()) {
              if (a instanceof AssignmentImpl.SetAssignmentImpl) {
                // check if this set assignment is for a multi-column and if it is then we
                // only want to keep the entries in the set that are not present
                // in the new multi key to avoid having DELETE generate for these
                // keys which are supposed to remain in place
                final AssignmentImpl.SetAssignmentImpl sa = (AssignmentImpl.SetAssignmentImpl)a;
                final FieldInfoImpl<?> f = table.getColumnImpl(a.getColumnName());

                if (f.isMultiKey()) {
                  if (oldval != null) {
                    final Collection<?> newset = (Collection<?>)sa.getValue();

                    if (!newset.isEmpty()) {
                      if (f.isCaseInsensitiveKey()) {
                        final Set<String> oldset = ((Collection<?>)oldval).stream()
                          .map(v -> (v != null) ? StringUtils.lowerCase(v.toString()) : null)
                          .collect(Collectors.toCollection(LinkedHashSet::new));

                        newset.forEach(
                          v -> oldset.remove((v != null) ? StringUtils.lowerCase(v.toString()) : null)
                        );
                        oldval = oldset;
                      } else {
                        final Set<?> oldset = new HashSet<>((Collection<?>)oldval); // clone it

                        oldset.removeAll(newset);
                        oldval = oldset;
                      }
                    }
                  }
                }
              }
            }
            old_values.put(a.getColumnName().toString(), oldval);
          }
        }
      }
      if (foundPK) {
        if (old_values != null) {
          // primary key has changed so we need to first do a delete in this
          // table which will be followed by a brand new insert
          init(new DeleteImpl<>(
            getPOJOContext(),
            table,
            usings.usings,
            ifExists,
            previousConditions.conditions,
            old_values, // pass our old values as override in the POJO
            mgr,
            bridge
          )).buildQueryString(table, builders);
        }
        // time to shift gears to a full insert in which case we must rely
        // on the whole POJO as the assignments might not be complete
        init(new InsertImpl<>(getPOJOContext(), table, usings.usings, mgr, bridge))
          .buildQueryStrings(table, builders);
        return;
      }
      builder.append(" SET ");
      // make sure we do not add any duplicates
      Utils.joinAndAppendWithNoDuplicates(
        getKeyspace(), table, null, mgr.getCodecRegistry(), builder, ",", as, null
      );
    } else { // nothing to set for this table
      return;
    }
    if (!where.clauses.isEmpty()) {
      // let's first preprocess and validate the clauses for this table
      final List<ClauseImpl> whereClauses = where.getClauses(table);
      final List<ClauseImpl> cs = new ArrayList<>(whereClauses.size()); // preserver order

      for (final ClauseImpl c: whereClauses) {
        andClause(table, cs, c);
      }
      if (cs.isEmpty()) { // nothing to select for this update so skip
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
      // we didn't have any multi-keys in the clauses so just update it based
      // on the given clause
      Utils.joinAndAppend(
        getKeyspace(), table, null, mgr.getCodecRegistry(), builder, " AND ", cs, null
      );
    } else { // no clauses provided, so add where clauses for all primary key columns
      try {
        final Map<String, Triple<Object, CQLDataType, TypeCodec<?>>> pkeys
          = getPOJOContext().getPrimaryKeyColumnValues(table.getName());

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
                final Set<Object> set = (Set<Object>)pset.getLeft();

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
              // now iterate all combination of these sets to generate update statements
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
          // we didn't have any multi-keys in the list (unlikely) so just update it
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
      if (table.getTable().type() == Table.Type.AUDIT) {
        // deal with AUDIT tables only if we were updating all from the POJO
        // with no clauses
        // otherwise leave it to the statements to deal with it
        if (assignments.hasAllFromObject() && where.clauses.isEmpty()) {
          // we must create an insert for this table if not already done
          // otherwise, simply add this table to the list of tables to handle
          if (insert == null) {
            insert = init(new InsertImpl<>(getPOJOContext(), table, usings.usings, mgr, bridge));
          } else { // add this table to the mix
            insert.into(table);
          }
          continue;
        } // else - fall-through to handle it normally
      } // else - STANDARD and DELETE tables are handled normally
      buildQueryStrings(table, builders);
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
   * @see org.helenus.driver.impl.StatementImpl#appendGroupSubType(java.lang.StringBuilder)
   */
  @Override
  protected void appendGroupSubType(StringBuilder builder) {
    if (isCounterOp()) {
      builder.append(" COUNTER");
    }
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
   * @see org.helenus.driver.Update#with(org.helenus.driver.Assignment[])
   */
  @Override
  public Assignments<T> with(Assignment... assignments) {
    return this.assignments.and(assignments);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#with()
   */
  @Override
  public Assignments<T> with() {
    return assignments;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#where(org.helenus.driver.Clause)
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
   * @see org.helenus.driver.Update#where()
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
   * @see org.helenus.driver.Update#ifExists()
   */
  @SuppressWarnings("synthetic-access")
  @Override
  public Update<T> ifExists() {
    org.apache.commons.lang3.Validate.isTrue(
      conditions.conditions.isEmpty(),
      "cannot combined additional conditions with IF EXISTS"
    );
    org.apache.commons.lang3.Validate.isTrue(
      previousConditions.conditions.isEmpty(),
      "cannot combined additional previous conditions with IF EXISTS"
    );
    this.ifExists = true;
    setDirty();
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#onlyIf(org.helenus.driver.Clause)
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
   * @see org.helenus.driver.Update#onlyIf()
   */
  @Override
  public Conditions<T> onlyIf() {
    org.apache.commons.lang3.Validate.isTrue(
      !ifExists,
      "cannot combined additional conditions with IF EXISTS"
    );
    return conditions;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#previouslyIf(org.helenus.driver.Clause)
   */
  @Override
  public Conditions<T> previouslyIf(Clause condition) {
    return previousConditions.and(condition);
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#previouslyIf()
   */
  @Override
  public Conditions<T> previouslyIf() {
    org.apache.commons.lang3.Validate.isTrue(
      !ifExists,
      "cannot combined additional previous conditions with IF EXISTS"
    );
    return previousConditions;
  }

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.Update#using(org.helenus.driver.Using)
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
   * @see org.helenus.driver.impl.StatementImpl#executeAsync0()
   */
  @SuppressWarnings("synthetic-access")
  @Override
  public VoidFuture executeAsync0() {
    // if we have no conditions then no need for special treatment of the response
    if (conditions.conditions.isEmpty()) {
      return super.executeAsync0();
    }
    return bridge.newVoidFuture(executeAsyncRaw0(), new VoidFuture.PostProcessor() {
      @Override
      public void postProcess(ResultSet result) {
        // update result set when using conditions have only one row
        // where the entry "[applied]" is a boolean indicating if the update was
        // successful and the rest are all the conditional values specified in
        // the UPDATE request

        // check if the condition was successful
        final Row row = result.one();

        if (row == null) {
          throw new UpdateNotAppliedException("no result row returned");
        }
        if (!row.getBool("[applied]")) {
          throw new UpdateNotAppliedException(row, "update not applied");
        }
        // else all good
      }
    });
  }

  /**
   * The <code>AssignmentsImpl</code> class defines the assignments of an UPDATE
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
  public static class AssignmentsImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, UpdateImpl<T>>
    implements Assignments<T> {
    /**
     * Holds the assignments for the statement.
     *
     * @author paouelle
     */
    private final List<AssignmentImpl> assignments = new ArrayList<>(8);

    /**
     * Holds the previous values for the statement.
     *
     * @author paouelle
     */
    private final Map<String, AssignmentImpl.WithOldValue> previous = new HashMap<>(8);

    /**
     * Flag indicating if all columns from the objects are being added using the
     * special {@link StatementBuilder#setAllFromObject} assignment.
     *
     * @author paouelle
     */
    private boolean hasAllFromObject = false;

    /**
     * Instantiates a new <code>AssignmentsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the encapsulated statement
     */
    AssignmentsImpl(UpdateImpl<T> statement) {
      super(statement);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#and(org.helenus.driver.Assignment[])
     */
    @Override
    public Assignments<T> and(Assignment... assignments) {
      org.apache.commons.lang3.Validate.notNull(
        assignments, "invalid null assignments"
      );
      for (final Assignment assignment: assignments) {
        org.apache.commons.lang3.Validate.notNull(
          assignment, "invalid null assignment"
        );
        org.apache.commons.lang3.Validate.isTrue(
          assignment instanceof AssignmentImpl,
          "unsupported class of assignments: %s",
          assignment.getClass().getName()
        );
        final AssignmentImpl a = (AssignmentImpl)assignment;

        statement.setCounterOp(a instanceof CounterAssignmentImpl);
        if (a instanceof AssignmentImpl.DelayedSetAllAssignmentImpl) {
          if (((AssignmentImpl.DelayedSetAllAssignmentImpl)a).object == null) {
            this.hasAllFromObject = true;
          }
        }
        if (!(a instanceof AssignmentImpl.DelayedWithObject)) {
          // pre-validate against any table
          getPOJOContext().getClassInfo().validateColumn(a.getColumnName().toString());
        }
        if (a instanceof AssignmentImpl.PreviousAssignmentImpl) {
          final AssignmentImpl.PreviousAssignmentImpl pa = (AssignmentImpl.PreviousAssignmentImpl)a;

          previous.put(pa.getColumnName().toString(), pa);
        } else if (a instanceof AssignmentImpl.ReplaceAssignmentImpl) {
          final AssignmentImpl.ReplaceAssignmentImpl ra = (AssignmentImpl.ReplaceAssignmentImpl)a;

          previous.put(ra.getColumnName().toString(), ra);
          this.assignments.add(a);
        } else {
          this.assignments.add(a);
        }
        setDirty();
      }
      return this;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#isEmpty()
     */
    @Override
    public boolean isEmpty() {
      return assignments.isEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#hasAllFromObject()
     */
    @Override
    public boolean hasAllFromObject() {
      return assignments.isEmpty() || hasAllFromObject;
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#where(org.helenus.driver.Clause)
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
     * @see org.helenus.driver.Update.Assignments#using(org.helenus.driver.Using)
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
     * @see org.helenus.driver.Update.Assignments#ifExists()
     */
    @Override
    public Update<T> ifExists() {
      return statement.ifExists();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#onlyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> onlyIf(Clause condition) {
      return statement.onlyIf(condition);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Assignments#previouslyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> previouslyIf(Clause condition) {
      return statement.previouslyIf(condition);
    }
  }

  /**
   * The <code>WhereImpl</code> class defines a WHERE clause for an UPDATE statement.
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
    extends ForwardingStatementImpl<Void, VoidFuture, T, UpdateImpl<T>>
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
    WhereImpl(UpdateImpl<T> statement) {
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
     * @see org.helenus.driver.Update.Where#and(org.helenus.driver.Clause)
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
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Where#with(org.helenus.driver.Assignment[])
     */
    @Override
    public Assignments<T> with(Assignment... assignments) {
      return statement.with(assignments);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Where#using(org.helenus.driver.Using)
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
     * @see org.helenus.driver.Update.Where#ifExists()
     */
    @Override
    public Update<T> ifExists() {
      return statement.ifExists();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Where#onlyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> onlyIf(Clause condition) {
      return statement.onlyIf(condition);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Where#previouslyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> previouslyIf(Clause condition) {
      return statement.previouslyIf(condition);
    }
  }

  /**
   * The <code>OptionsImpl</code> class defines the options of an UPDATE statement.
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
    extends ForwardingStatementImpl<Void, VoidFuture, T, UpdateImpl<T>>
    implements Options<T> {
    /**
     * Holds the list of "USINGS" options.
     *
     * @author paouelle
     */
    private final List<UsingImpl<?>> usings = new ArrayList<>(5);

    /**
     * Instantiates a new <code>OptionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating options
     */
    OptionsImpl(UpdateImpl<T> statement) {
      super(statement);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Options#and(org.helenus.driver.Using)
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
     * @see org.helenus.driver.Update.Options#with(org.helenus.driver.Assignment[])
     */
    @Override
    public Assignments<T> with(Assignment... assignments) {
      return statement.with(assignments);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Options#ifExists()
     */
    @Override
    public Update<T> ifExists() {
      return statement.ifExists();
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Options#onlyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> onlyIf(Clause condition) {
      return statement.onlyIf(condition);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Options#previouslyIf(org.helenus.driver.Clause)
     */
    @Override
    public Conditions<T> previouslyIf(Clause condition) {
      return statement.previouslyIf(condition);
    }
  }

  /**
   * Conditions for an UDPATE statement.
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
   * @version 1 - Jan 19, 2015 - paouelle - Creation
   *
   * @param <T> The type of POJO associated with the statement.
   *
   * @since 1.0
   */
  public static class ConditionsImpl<T>
    extends ForwardingStatementImpl<Void, VoidFuture, T, UpdateImpl<T>>
    implements Conditions<T> {
    /**
     * Holds the list of conditions for the statement
     *
     * @author paouelle
     */
    private final List<ClauseImpl> conditions = new ArrayList<>(10);

    /**
     * Instantiates a new <code>ConditionsImpl</code> object.
     *
     * @author paouelle
     *
     * @param statement the non-<code>null</code> statement for which we are
     *        creating conditions
     */
    ConditionsImpl(UpdateImpl<T> statement) {
      super(statement);
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
     * @see org.helenus.driver.Update.Conditions#with(org.helenus.driver.Assignment[])
     */
    @Override
    public Assignments<T> with(Assignment... assignments) {
      return statement.with(assignments);
    }

    /**
     * {@inheritDoc}
     *
     * @author paouelle
     *
     * @see org.helenus.driver.Update.Conditions#where(org.helenus.driver.Clause)
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
     * @see org.helenus.driver.Update.Conditions#using(org.helenus.driver.Using)
     */
    @Override
    public Options<T> using(Using<?> using) {
      return statement.using(using);
    }
  }
}
