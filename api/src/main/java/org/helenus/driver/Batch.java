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
package org.helenus.driver;

import java.util.Optional;
import java.util.stream.Stream;

import com.google.common.util.concurrent.ListenableFuture;

import org.helenus.driver.info.ClassInfo;
import org.helenus.util.function.ERunnable;

/**
 * The <code>Batch</code> interface extends the functionality of Cassandra's
 * {@link com.datastax.driver.core.querybuilder.Batch} class to provide
 * support for POJOs.
 *
 * @copyright 2015-2016 The Helenus Driver Project Authors
 *
 * @author  The Helenus Driver Project Authors
 * @version 1 - Jan 15, 2015 - paouelle - Creation
 *
 * @since 1.0
 */
public interface Batch
  extends ParentStatement, BatchableStatement<Void, VoidFuture> {
  /**
   * Holds the max size of the batch after which we should be committing to
   * Cassandra as part of a batch.
   * <p>
   * <i>Note:</i> With regard to a large number of records in a batch mutation
   * there are some potential issues. Each row becomes a task in the write
   * thread pool on each Cassandra replica. If a single client sends 1,000 rows
   * in a mutation it will take time for the (default) 32 threads in the write
   * pool to work through the mutations. While they are doing this other
   * clients/requests will appear to be starved/stalled. There are also issues
   * with the max message size in thrift and cql over thrift. In addition,
   * Cassandra 2.2.0 reduced the default size in bytes it will accept for a batch
   * before it fails it to 50K.
   *
   * @author paouelle
   */
  public final static int RECOMMENDED_MAX = 5;

  /**
   * {@inheritDoc}
   * <p>
   * Gets the keyspace of the first statement in this batch.
   *
   * @author paouelle
   *
   * @return the keyspace from the first statement in this batch or
   *         <code>null</code> if the batch is empty
   *
   * @see org.helenus.driver.GenericStatement#getKeyspace()
   */
  @Override
  public String getKeyspace();

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.ParentStatement#add(org.helenus.driver.BatchableStatement)
   */
  @Override
  public <R, F extends ListenableFuture<R>> Batch add(BatchableStatement<R, F> statement);

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.ParentStatement#add(com.datastax.driver.core.RegularStatement)
   */
  @Override
  public Batch add(com.datastax.driver.core.RegularStatement statement);

  /**
   * {@inheritDoc}
   *
   * @author paouelle
   *
   * @see org.helenus.driver.ParentStatement#addErrorHandler(org.helenus.util.function.ERunnable)
   */
  @Override
  public Batch addErrorHandler(ERunnable<?> run);

  /**
   * Checks if this batch has reached the recommended size for a batch in
   * system with a high number of concurrent writers.
   *
   * @author paouelle
   *
   * @return <code>true</code> if the recommended size has been reached or
   *         exceeded for this batch; <code>false</code> otherwise
   */
  public boolean hasReachedRecommendedSize();

  /**
   * Checks if this batch has reached the recommended size for a batch in
   * system with a high number of concurrent writers.
   * <p>
   * <i>Note:</i> This method will take into account the number of tables defined
   * for the specified POJO class.
   *
   * @author paouelle
   *
   * @param  clazz the class of POJO for which to verify if the batch has reached
   *         the recommended size
   * @return <code>true</code> if the recommended size has been reached or
   *         exceeded for this batch; <code>false</code> otherwise
   */
  public boolean hasReachedRecommendedSizeFor(Class<?> clazz);

  /**
   * Checks if this batch has reached the recommended size for a batch in
   * system with a high number of concurrent writers.
   * <p>
   * <i>Note:</i> This method will take into account the number of tables defined
   * for the specified POJO class.
   *
   * @author paouelle
   *
   * @param  cinfo the class of POJO for which to verify if the batch has reached
   *         the recommended size
   * @return <code>true</code> if the recommended size has been reached or
   *         exceeded for this batch; <code>false</code> otherwise
   */
  public boolean hasReachedRecommendedSizeFor(ClassInfo<?> cinfo);

  /**
   * Adds a new options for this BATCH statement.
   *
   * @author paouelle
   *
   * @param  using the option to add
   * @return the options of this BATCH statement
   */
  public Options using(Using<?> using);

  /**
   * Gets all options registered with this statement.
   *
   * @author paouelle
   *
   * @return a non-<code>null</code> stream of all options registered with this
   *         statement
   */
  public Stream<Using<?>> usings();

  /**
   * Gets an option registered with this statement given its name
   *
   * @author paouelle
   *
   * @param <U> the type of the option
   *
   * @param  name the name of the option to retrieve
   * @return the registered option with the given name or empty if none registered
   */
  public <U> Optional<Using<U>> getUsing(String name);

  /**
   * Duplicates this batch statement.
   * <p>
   * <i>Note:</i> The registered recorder is not copied over to the duplicated
   * batch.
   *
   * @author paouelle
   *
   * @return a new batch statement which is a duplicate of this one
   */
  public Batch duplicate();

  /**
   * Duplicates this batch statement.
   *
   * @author paouelle
   *
   * @param  recorder the recorder to register with the duplicated batch
   * @return a new batch statement which is a duplicate of this one
   * @throws NullPointerException if <code>recorder</code> is <code>null</code>
   */
  public Batch duplicate(Recorder recorder);

  /**
   * The <code>Options</code> interface defines the options of a BATCH statement.
   *
   * @copyright 2015-2016 The Helenus Driver Project Authors
   *
   * @author  The Helenus Driver Project Authors
   * @version 1 - Jan 15, 2015 - paouelle - Creation
   *
   * @since 1.0
   */
  public interface Options
    extends GenericStatement<Void, VoidFuture>, BatchableStatement<Void, VoidFuture> {
    /**
     * Adds the provided option.
     *
     * @author paouelle
     *
     * @param  using a BATCH option
     * @return this {@code Options} object
     */
    public Options and(Using<?> using);

    /**
     * Adds a new statement to the BATCH statement these options are part of.
     *
     * @author paouelle
     *
     * @param <R> The type of result returned when executing the statement to record
     * @param <F> The type of future result returned when executing the statement
     *            to record
     *
     * @param  statement the new statement to add
     * @return this batch
     * @throws NullPointerException if <code>statement</code> is <code>null</code>
     * @throws IllegalArgumentException if counter and non-counter operations
     *         are mixed or if the statement represents a "select" statement or a
     *         "batch" statement  or if the statement is not of a supported class
     */
    public <R, F extends ListenableFuture<R>> Batch add(
      BatchableStatement<R, F> statement
    );

    /**
     * Adds a new raw Cassandra statement to the BATCH statement these options
     * are part of.
     *
     * @author paouelle
     *
     * @param  statement the new statement to add
     * @return this batch
     * @throws NullPointerException if <code>statement</code> is <code>null</code>
     * @throws IllegalArgumentException if counter and non-counter operations
     *         are mixed or if the statement represents a "select" statement or a
     *         "batch" statement
     */
    public Batch add(com.datastax.driver.core.RegularStatement statement);

    /**
     * Registers an error handler with this batch. Error handlers are simply
     * attached to the batch and must be specifically executed via the
     * {@link #runErrorHandlers} method by the user when an error occurs either
     * from the execution of the batch of before executing the batch to make sure
     * that allocated resources can be properly released if no longer required.
     *
     * @author paouelle
     *
     * @param  run the error handler to register
     * @return this batch
     * @throws NullPointerException if <code>run</code> is <code>null</code>
     */
    public Batch addErrorHandler(ERunnable<?> run);
  }
}
