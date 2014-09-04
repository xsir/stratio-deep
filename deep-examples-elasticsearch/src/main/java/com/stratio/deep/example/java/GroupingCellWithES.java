/*
 * Copyright 2014, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.deep.example.java;


import com.google.common.io.Resources;
import com.stratio.deep.commons.config.ExtractorConfig;
import com.stratio.deep.core.context.DeepSparkContext;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.entity.ESCell;
import com.stratio.deep.extractor.ESCellExtractor;
import com.stratio.deep.commons.extractor.server.ExtractorServer;
import com.stratio.deep.commons.extractor.utils.ExtractorConstants;
import com.stratio.deep.utils.ContextProperties;
import org.apache.log4j.Logger;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.rdd.RDD;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.node.Node;
import scala.Tuple2;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

/**
 * Created by rcrespo on 25/06/14.
 */
public final class GroupingCellWithES {
    private static final Logger LOG = Logger.getLogger(GroupingCellWithES.class);

    /* used to perform external tests */
    private static Double avg;
    private static Double variance;
    private static Double stddev;
    private static Double count;
    private static Long counts;
    public static final String MESSAGE_TEST = "new message test";
    public static final Integer PORT = 9200;
    public static final String HOST = "localhost";
    public static final String DATABASE = "twitter/tweet";
    public static final String DATABASE_OUTPUT = "twitter/tweet2";
    public static final String COLLECTION_OUTPUT = "output";
    public static final String COLLECTION_OUTPUT_CELL = "outputcell";
    public static final String DATA_SET_NAME = "divineComedy.json";
    public static final Long WORD_COUNT_SPECTED = 3833L;
    private static Node node = null;
    private static Client client = null;

    private GroupingCellWithES() {
    }
    public static void init() throws IOException, ExecutionException, InterruptedException {
        node = nodeBuilder().local(true).clusterName("localhost").node();
        client = node.client();
        dataSetImport();
    }

    private static void dataSetImport() throws IOException, ExecutionException, InterruptedException {

        URL url = Resources.getResource(DATA_SET_NAME);

        IndexResponse response = client.prepareIndex("book", "test").setCreate(true)
                .setSource(url.getFile())
                .execute()
                .actionGet();
        try {
            CountResponse action = client.prepareCount("book").setTypes("test")
                    .execute()
                    .actionGet();
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public static void main(String[] args) {

        doMain(args);
    }


    public static void doMain(String[] args) {

        String job      = "java:groupingCellWithES";
        String host     = "localhost:9200";
        String databaseIn = "book/word";
        String index    = "book";
        String type     = "test";
        String databaseOut = "entity/output";
        //Call async the Extractor netty Server
        ExecutorService es = Executors.newFixedThreadPool(1);
        final Future future = es.submit(new Callable() {
            public Object call() throws Exception {
                ExtractorServer.main(null);
                return null;
            }
        });

        // Creating the Deep Context
        ContextProperties p = new ContextProperties(args);
        DeepSparkContext deepContext = new DeepSparkContext(p.getCluster(), job, p.getSparkHome(), p.getJars());


        // Creating a configuration for the Extractor and initialize it
        ExtractorConfig<Cells> config = new ExtractorConfig();

        Map<String, String> values = new HashMap<>();

        values.put(ExtractorConstants.DATABASE,    databaseIn);
        values.put(ExtractorConstants.HOST,        host );


        config.setExtractorImplClass(ESCellExtractor.class);
        config.setValues(values);

        // Creating the RDD
        RDD<Cells> rdd =  deepContext.createRDD(config);


        JavaRDD<String> words =rdd.toJavaRDD().flatMap(new FlatMapFunction<Cells, String>() {
            @Override
            public Iterable<String> call(Cells cells) throws Exception {

                List<String> words = new ArrayList<>();
                for (Cells canto : (List<Cells>) cells.getCellByName("cantos").getCellValue()) {
                    words.addAll(Arrays.asList(((String) canto.getCellByName("text").getCellValue()).split(" ")));
                }
                return words;
            }
        });


        JavaPairRDD<String, Integer> wordCount = words.mapToPair(new PairFunction<String, String, Integer>() {
            @Override
            public Tuple2<String, Integer> call(String s) throws Exception {
                return new Tuple2<String, Integer>(s,1);
            }
        });


        JavaPairRDD<String, Integer>  wordCountReduced = wordCount.reduceByKey(new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer call(Integer integer, Integer integer2) throws Exception {
                return integer + integer2;
            }
        });

        JavaRDD<Cells>  outputRDD =  wordCountReduced.map(new Function<Tuple2<String, Integer>, Cells>() {
            @Override
            public Cells call(Tuple2<String, Integer> stringIntegerTuple2) throws Exception {
                return new Cells(ESCell.create("word", stringIntegerTuple2._1()) , ESCell.create("count", stringIntegerTuple2._2()));
            }
        });

        counts = rdd.count();
        LOG.info("----------------------------- Num of rows: " + counts);

        ExtractorConfig<Cells> outputConfigEntity = new ExtractorConfig<>();
        outputConfigEntity.putValue(ExtractorConstants.HOST, host).putValue(ExtractorConstants.DATABASE, databaseOut);
        outputConfigEntity.setExtractorImplClass(ESCellExtractor.class);


        deepContext.saveRDD(outputRDD.rdd(), outputConfigEntity);




        ExtractorServer.close();
        deepContext.stop();
    }
}