/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.commit;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.model.HoodieBaseFile;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.util.queue.BoundedInMemoryExecutor;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.io.HoodieMergeHandle;
import org.apache.hudi.io.storage.HoodieFileReader;
import org.apache.hudi.io.storage.HoodieFileReaderFactory;
import org.apache.hudi.table.HoodieTable;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.hadoop.conf.Configuration;

import java.io.IOException;
import java.util.Iterator;

public class HoodieMergeHelper<T extends HoodieRecordPayload> extends
    BaseMergeHelper<T, HoodieData<HoodieRecord<T>>, HoodieData<HoodieKey>, HoodieData<WriteStatus>> {

  private HoodieMergeHelper() {
  }

  private static class MergeHelperHolder {
    private static final HoodieMergeHelper HOODIE_MERGE_HELPER = new HoodieMergeHelper<>();
  }

  public static HoodieMergeHelper newInstance() {
    return MergeHelperHolder.HOODIE_MERGE_HELPER;
  }

  @Override
  public void runMerge(HoodieTable<T, HoodieData<HoodieRecord<T>>, HoodieData<HoodieKey>, HoodieData<WriteStatus>> table,
                       HoodieMergeHandle<T, HoodieData<HoodieRecord<T>>, HoodieData<HoodieKey>, HoodieData<WriteStatus>> mergeHandle) throws IOException {
    final boolean externalSchemaTransformation = table.getConfig().shouldUseExternalSchemaTransformation();
    Configuration cfgForHoodieFile = new Configuration(table.getHadoopConf());
    HoodieBaseFile baseFile = mergeHandle.baseFileForMerge();

    final GenericDatumWriter<GenericRecord> gWriter;
    final GenericDatumReader<GenericRecord> gReader;
    Schema readSchema;
    if (externalSchemaTransformation || baseFile.getBootstrapBaseFile().isPresent()) {
      readSchema = HoodieFileReaderFactory.getFileReader(table.getHadoopConf(), mergeHandle.getOldFilePath()).getSchema();
      gWriter = new GenericDatumWriter<>(readSchema);
      gReader = new GenericDatumReader<>(readSchema, mergeHandle.getWriterSchemaWithMetaFields());
    } else {
      gReader = null;
      gWriter = null;
      readSchema = mergeHandle.getWriterSchemaWithMetaFields();
    }

    BoundedInMemoryExecutor<GenericRecord, GenericRecord, Void> wrapper = null;
    HoodieFileReader<GenericRecord> reader = HoodieFileReaderFactory.getFileReader(cfgForHoodieFile, mergeHandle.getOldFilePath());
    try {
      final Iterator<GenericRecord> readerIterator;
      if (baseFile.getBootstrapBaseFile().isPresent()) {
        readerIterator = getMergingIterator(table, mergeHandle, baseFile, reader, readSchema, externalSchemaTransformation);
      } else {
        readerIterator = reader.getRecordIterator(readSchema);
      }

      ThreadLocal<BinaryEncoder> encoderCache = new ThreadLocal<>();
      ThreadLocal<BinaryDecoder> decoderCache = new ThreadLocal<>();
      wrapper = new BoundedInMemoryExecutor(table.getConfig().getWriteBufferLimitBytes(), readerIterator,
          new UpdateHandler(mergeHandle), record -> {
        if (!externalSchemaTransformation) {
          return record;
        }
        return transformRecordBasedOnNewSchema(gReader, gWriter, encoderCache, decoderCache, (GenericRecord) record);
      }, table.getPreExecuteRunnable());
      wrapper.execute();
    } catch (Exception e) {
      throw new HoodieException(e);
    } finally {
      if (reader != null) {
        reader.close();
      }
      mergeHandle.close();
      if (null != wrapper) {
        wrapper.shutdownNow();
      }
    }
  }
}
