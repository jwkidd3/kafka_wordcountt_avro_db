/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package myapps;

import myapps.avro.AvroWordCount;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;

import java.io.*;
import java.sql.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In this example, we implement a simple WordCount program using the high-level
 * Streams DSL that reads from a source topic "streams-plaintext-input", where
 * the values of messages represent lines of text, split each text line into
 * words and then compute the word occurence histogram, write the continuous
 * updated histogram into a topic "streams-wordcount-output" where each record
 * is an updated count of a single word.
 */
public class WordCount {
	String url = "jdbc:h2:file:~/wordCount";
	String user = "sa";
	String passwd = "";
	Connection con;
	
	public static void main(String[] args) throws Exception {
		WordCount myprogram = new WordCount();
		myprogram.start();
	}

	public void start() throws Exception {

		// KAFKA STREAM 1
		Properties props = new Properties();
		props.put(StreamsConfig.APPLICATION_ID_CONFIG, "streams-wordcount");
		props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
		props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

		// WORDCOUNT
		final StreamsBuilder builder = new StreamsBuilder();
		KStream<String, Long> kstream = builder.<String, String>stream("streams-plaintext-input")
				.flatMapValues(value -> Arrays.asList(value.toLowerCase(Locale.getDefault()).split("\\W+")))
				.groupBy((key, value) -> value)
				.count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("counts-store"))
				.toStream();

		// SUBMIT STREAM AVRO ENCODED
		KStream<String, String> kstreamAvro = kstream.mapValues((key, value) -> {
			AvroWordCount awc = new AvroWordCount(key, value.intValue());
			return awc.toString();
		});
		kstreamAvro.to("streams-wordcount-output-avro");

		// FINALIZE FIRST TOPOLOGY
		final Topology topology = builder.build();
		final KafkaStreams streams = new KafkaStreams(topology, props);

		//KAFKA STREAM 2
		Properties props2 = new Properties();
		props2.put(StreamsConfig.APPLICATION_ID_CONFIG, "streams-wordcount2");
		props2.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		props2.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
		props2.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

		// AVRO DECODE
		final StreamsBuilder builder2 = new StreamsBuilder();
		KStream<String, Long> kstream2 = builder2.<String, String>stream("streams-wordcount-output-avro")
				.mapValues((String value) -> {
					return ((Integer) deSerealizeAvroWordCountJSON(value.getBytes()).get("wordcount")).longValue();
				});


		// DB CONNECTION
		Class.forName("org.h2.Driver");
		con = DriverManager.getConnection(url, user, passwd);
		String query = "CREATE TABLE IF NOT EXISTS wordCount(WORD VARCHAR(255) PRIMARY KEY, WORDCOUNT VARCHAR(255) );";
		try {
			Statement st = con.createStatement();
			st.execute(query);
			con.setAutoCommit(true); //useless
		} catch (SQLException e) {
			e.printStackTrace();
		}

		// WRITE TO DB
		kstream2.foreach((k, v) -> insertOrUpdate(k, v));
		kstream2.to("streams-wordcount-output", Produced.with(Serdes.String(), Serdes.Long()));

		// FINALIZE 2nd TOPOLOGY
		final Topology topology2 = builder2.build();
		final KafkaStreams streams2 = new KafkaStreams(topology2, props2);

		final CountDownLatch latch = new CountDownLatch(1);

		// attach shutdown handler to catch control-c
		Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
			@Override
			public void run() {
				try {
					con.commit(); // useless
					con.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
				streams.close();
				streams2.close();
				latch.countDown();
			}
		});

		try {
			streams.start();
			streams2.start();
			latch.await();
		} catch (Throwable e) {
			System.exit(1);
		}
		System.exit(0);
	}

	private void insertOrUpdate(String k, Long v) {
		String query = "MERGE INTO wordCount KEY (WORD) VALUES ('" + k + "', '" + v.toString() + "');";
		try {
			System.out.println(con.getAutoCommit());
			Statement st = con.createStatement();
			st.execute(query);
			con.commit(); // useless
		} catch (SQLException ex) {
			Logger lgr = Logger.getLogger(WordCount.class.getName());
			lgr.log(Level.SEVERE, ex.getMessage(), ex);
		}
		System.out.println("Key = " + k + ", Value = " + v.toString());
	}

	public AvroWordCount deSerealizeAvroWordCountJSON(byte[] data) {
		DatumReader<AvroWordCount> reader
				= new SpecificDatumReader<>(AvroWordCount.class);
		Decoder decoder = null;
		try {
			decoder = DecoderFactory.get().jsonDecoder(
					AvroWordCount.getClassSchema(), new String(data));
			return reader.read(null, decoder);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
}
