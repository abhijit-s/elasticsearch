/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket.composite;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.index.fielddata.FieldData;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.AggregatorTestCase;
import org.elasticsearch.search.aggregations.LeafBucketCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.index.mapper.NumberFieldMapper.NumberType.DOUBLE;
import static org.elasticsearch.index.mapper.NumberFieldMapper.NumberType.LONG;
import static org.hamcrest.Matchers.equalTo;

public class CompositeValuesCollectorQueueTests extends AggregatorTestCase {
    static class ClassAndName {
        final MappedFieldType fieldType;
        final Class<? extends Comparable<?>> clazz;

        ClassAndName(MappedFieldType fieldType, Class<? extends Comparable<?>> clazz) {
            this.fieldType = fieldType;
            this.clazz = clazz;
        }
    }

    public void testRandomLong() throws IOException {
        testRandomCase(new ClassAndName(createNumber("long", LONG) , Long.class));
    }

    public void testRandomDouble() throws IOException {
        testRandomCase(new ClassAndName(createNumber("double", DOUBLE) , Double.class));
    }

    public void testRandomDoubleAndLong() throws IOException {
        testRandomCase(new ClassAndName(createNumber("double", DOUBLE), Double.class),
            new ClassAndName(createNumber("long", LONG),  Long.class));
    }

    public void testRandomDoubleAndKeyword() throws IOException {
        testRandomCase(new ClassAndName(createNumber("double", DOUBLE), Double.class),
            new ClassAndName(createKeyword("keyword"), BytesRef.class));
    }

    public void testRandomKeyword() throws IOException {
        testRandomCase(new ClassAndName(createKeyword("keyword"), BytesRef.class));
    }

    public void testRandomLongAndKeyword() throws IOException {
        testRandomCase(new ClassAndName(createNumber("long", LONG),  Long.class),
            new ClassAndName(createKeyword("keyword"), BytesRef.class));
    }

    public void testRandomLongAndDouble() throws IOException {
        testRandomCase(new ClassAndName(createNumber("long", LONG),  Long.class),
            new ClassAndName(createNumber("double", DOUBLE) , Double.class));
    }

    public void testRandomKeywordAndLong() throws IOException {
        testRandomCase(new ClassAndName(createKeyword("keyword"), BytesRef.class),
            new ClassAndName(createNumber("long", LONG), Long.class));
    }

    public void testRandomKeywordAndDouble() throws IOException {
        testRandomCase(new ClassAndName(createKeyword("keyword"), BytesRef.class),
            new ClassAndName(createNumber("double", DOUBLE), Double.class));
    }

    public void testRandom() throws IOException {
        int numTypes = randomIntBetween(3, 8);
        ClassAndName[] types = new ClassAndName[numTypes];
        for (int i = 0; i < numTypes; i++) {
            int rand = randomIntBetween(0, 2);
            switch (rand) {
                case 0:
                    types[i] = new ClassAndName(createNumber(Integer.toString(i), LONG), Long.class);
                    break;
                case 1:
                    types[i] = new ClassAndName(createNumber(Integer.toString(i), DOUBLE), Double.class);
                    break;
                case 2:
                    types[i] = new ClassAndName(createKeyword(Integer.toString(i)), BytesRef.class);
                    break;
                default:
                    assert(false);
            }
        }
        testRandomCase(types);
    }

    private void testRandomCase(ClassAndName... types) throws IOException {
        testRandomCase(true, true, types);
        testRandomCase(true, false, types);
        testRandomCase(false, true, types);
        testRandomCase(false, false, types);
    }

    private void testRandomCase(boolean forceMerge, boolean missingBucket, ClassAndName... types) throws IOException {
        final BigArrays bigArrays = BigArrays.NON_RECYCLING_INSTANCE;
        int numDocs = randomIntBetween(50, 100);
        List<Comparable<?>[]> possibleValues = new ArrayList<>();
        for (ClassAndName type : types) {
            final Comparable<?>[] values;
            int numValues = randomIntBetween(1, numDocs * 2);
            values = new Comparable[numValues];
            if (type.clazz == Long.class) {
                for (int i = 0; i < numValues; i++) {
                    values[i] = randomLong();
                }
            } else if (type.clazz == Double.class) {
                for (int i = 0; i < numValues; i++) {
                    values[i] = randomDouble();
                }
            } else if (type.clazz == BytesRef.class) {
                for (int i = 0; i < numValues; i++) {
                    values[i] = new BytesRef(randomAlphaOfLengthBetween(5, 50));
                }
            } else {
                assert (false);
            }
            possibleValues.add(values);
        }

        Set<CompositeKey> keys = new HashSet<>();
        try (Directory directory = newDirectory()) {
            try (RandomIndexWriter indexWriter = new RandomIndexWriter(random(), directory, new KeywordAnalyzer())) {
                for (int i = 0; i < numDocs; i++) {
                    Document document = new Document();
                    List<List<Comparable<?>>> docValues = new ArrayList<>();
                    boolean hasAllField = true;
                    for (int j = 0; j < types.length; j++) {
                        int numValues = randomIntBetween(0, 5);
                        List<Comparable<?>> values = new ArrayList<>();
                        if (numValues == 0) {
                            hasAllField = false;
                            if (missingBucket) {
                                values.add(null);
                            }
                        } else {
                            for (int k = 0; k < numValues; k++) {
                                values.add(possibleValues.get(j)[randomIntBetween(0, possibleValues.get(j).length - 1)]);
                                if (types[j].clazz == Long.class) {
                                    long value = (Long) values.get(k);
                                    document.add(new SortedNumericDocValuesField(types[j].fieldType.name(), value));
                                    document.add(new LongPoint(types[j].fieldType.name(), value));
                                } else if (types[j].clazz == Double.class) {
                                    document.add(new SortedNumericDocValuesField(types[j].fieldType.name(),
                                        NumericUtils.doubleToSortableLong((Double) values.get(k))));
                                } else if (types[j].clazz == BytesRef.class) {
                                    BytesRef value = (BytesRef) values.get(k);
                                    document.add(new SortedSetDocValuesField(types[j].fieldType.name(), (BytesRef) values.get(k)));
                                    document.add(new TextField(types[j].fieldType.name(), value.utf8ToString(), Field.Store.NO));
                                } else {
                                    assert (false);
                                }
                            }
                        }
                        docValues.add(values);
                    }
                    if (hasAllField || missingBucket) {
                        List<CompositeKey> comb = createListCombinations(docValues);
                        keys.addAll(comb);
                    }
                    indexWriter.addDocument(document);
                }
                if (forceMerge) {
                    indexWriter.forceMerge(1);
                }
            }
            IndexReader reader = DirectoryReader.open(directory);
            int size = randomIntBetween(1, keys.size());
            SingleDimensionValuesSource<?>[] sources = new SingleDimensionValuesSource[types.length];
            for (int i = 0; i < types.length; i++) {
                final MappedFieldType fieldType = types[i].fieldType;
                if (types[i].clazz == Long.class) {
                    sources[i] = new LongValuesSource(
                        bigArrays,
                        fieldType,
                        context -> DocValues.getSortedNumeric(context.reader(), fieldType.name()),
                        value -> value,
                        DocValueFormat.RAW,
                        missingBucket,
                        size,
                        1
                    );
                } else if (types[i].clazz == Double.class) {
                    sources[i] = new DoubleValuesSource(
                        bigArrays,
                        fieldType,
                        context -> FieldData.sortableLongBitsToDoubles(DocValues.getSortedNumeric(context.reader(), fieldType.name())),
                        DocValueFormat.RAW,
                        missingBucket,
                        size,
                        1
                    );
                } else if (types[i].clazz == BytesRef.class) {
                    if (forceMerge) {
                        // we don't create global ordinals but we test this mode when the reader has a single segment
                        // since ordinals are global in this case.
                        sources[i] = new GlobalOrdinalValuesSource(
                            bigArrays,
                            fieldType,
                            context -> DocValues.getSortedSet(context.reader(), fieldType.name()),
                            DocValueFormat.RAW,
                            missingBucket,
                            size,
                            1
                        );
                    } else {
                        sources[i] = new BinaryValuesSource(
                            bigArrays,
                            (b) -> {},
                            fieldType,
                            context -> FieldData.toString(DocValues.getSortedSet(context.reader(), fieldType.name())),
                            DocValueFormat.RAW,
                            missingBucket,
                            size,
                            1
                        );
                    }
                } else {
                    assert(false);
                }
            }
            CompositeKey[] expected = keys.toArray(new CompositeKey[0]);
            Arrays.sort(expected, (a, b) -> compareKey(a, b));
            for (boolean withProducer : new boolean[] {true, false}) {
                int pos = 0;
                CompositeKey last = null;
                while (pos < size) {
                    final CompositeValuesCollectorQueue queue =
                        new CompositeValuesCollectorQueue(BigArrays.NON_RECYCLING_INSTANCE, sources, size, last);
                    final SortedDocsProducer docsProducer = sources[0].createSortedDocsProducerOrNull(reader, new MatchAllDocsQuery());
                    for (LeafReaderContext leafReaderContext : reader.leaves()) {
                        final LeafBucketCollector leafCollector = new LeafBucketCollector() {
                            @Override
                            public void collect(int doc, long bucket) throws IOException {
                                queue.addIfCompetitive();
                            }
                        };
                        if (docsProducer != null && withProducer) {
                            assertEquals(DocIdSet.EMPTY,
                                docsProducer.processLeaf(new MatchAllDocsQuery(), queue, leafReaderContext, false));
                        } else {
                            final LeafBucketCollector queueCollector = queue.getLeafCollector(leafReaderContext, leafCollector);
                            final Bits liveDocs = leafReaderContext.reader().getLiveDocs();
                            for (int i = 0; i < leafReaderContext.reader().maxDoc(); i++) {
                                if (liveDocs == null || liveDocs.get(i)) {
                                    queueCollector.collect(i);
                                }
                            }
                        }
                    }
                    assertEquals(size, Math.min(queue.size(), expected.length - pos));
                    int ptr = 0;
                    for (int slot : queue.getSortedSlot()) {
                        CompositeKey key = queue.toCompositeKey(slot);
                        assertThat(key, equalTo(expected[ptr++]));
                        last = key;
                    }
                    pos += queue.size();
                }
            }
            reader.close();
        }
    }

    private static MappedFieldType createNumber(String name, NumberFieldMapper.NumberType type) {
        MappedFieldType fieldType = new NumberFieldMapper.NumberFieldType(type);
        fieldType.setIndexOptions(IndexOptions.DOCS);
        fieldType.setName(name);
        fieldType.setHasDocValues(true);
        fieldType.freeze();
        return fieldType;
    }

    private static MappedFieldType createKeyword(String name) {
        MappedFieldType fieldType = new KeywordFieldMapper.KeywordFieldType();
        fieldType.setIndexOptions(IndexOptions.DOCS);
        fieldType.setName(name);
        fieldType.setHasDocValues(true);
        fieldType.freeze();
        return fieldType;
    }

    private static int compareKey(CompositeKey key1, CompositeKey key2) {
        assert key1.size() == key2.size();
        for (int i = 0; i < key1.size(); i++) {
            if (key1.get(i) == null) {
                if (key2.get(i) == null) {
                    continue;
                }
                return -1;
            } else if (key2.get(i) == null) {
                return 1;
            }
            Comparable<Object> cmp1 = (Comparable<Object>) key1.get(i);
            int cmp = cmp1.compareTo(key2.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static List<CompositeKey> createListCombinations(List<List<Comparable<?>>> values) {
        List<CompositeKey> keys = new ArrayList<>();
        createListCombinations(new Comparable[values.size()], values, 0, values.size(), keys);
        return keys;
    }

    private static void createListCombinations(Comparable<?>[] key, List<List<Comparable<?>>> values,
                                               int pos, int maxPos, List<CompositeKey> keys) {
        if (pos == maxPos) {
            keys.add(new CompositeKey(key.clone()));
        } else {
            for (Comparable<?> val : values.get(pos)) {
                key[pos] = val;
                createListCombinations(key, values, pos + 1, maxPos, keys);
            }
        }
    }
}
