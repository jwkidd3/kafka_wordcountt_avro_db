# Progetto di esempio Kafka, Avro e Database
## Cosa è
Questo è un progetto di esempio Kafka. 
## Cosa è Kafka
Kafka serve per gestire code di messaggi all'interno di applicazioni.  
Le code sono chiamate topic, chi scrive su un topic è detto producer, chi legge da un topic è detto consumer.  
## Dettagli applicativo
Questo progetto di esempio prende messaggi in ingresso sulla coda `streams-plaintext-input` da terminale.  
L'applicativo di esempio legge da questa coda man mano che i messaggi arrivano, ogni messaggio viene spezzettato in parole, per ogni parola scrive un oggetto wordCount JSON codificato tramite Avro su `streams-wordcount-output-avro`, legge da quest'ultimo stream decodificando il JSON, scrive sul database le informazioni relative al wordCount e produce le stesse informazioni sullo stream `streams-wordcount-output`.
## Istruzioni
### Dipendenze
Questo progetto è stato sviluppato per Windows.  
Installare le dipendenze:  
- maven  
Scaricare maven e aggiungerlo al path  
- Java 8  
Questo progetto richiede Java 8, le altre versioni non vanno bene.  
Modificare java_home in modo che punti alla giusta versione di Java  
- Kafka  
Estrarre Kafka sul desktop in una cartella di nome kafka
- WordCountToDB  
Scaricare questo progetto sul desktop  
- H2 DB server  
Per utilizzare H2 Console
### Lanciare l'applicativo
IN UN NUOVO TERMINALE
```
title zookeeper
cd Desktop\kafka
bin\windows\zookeeper-server-start.bat config\zookeeper.properties
```

IN UN NUOVO TERMINALE
```
title kafka server broker 0
cd Desktop\kafka
bin\windows\kafka-server-start.bat config\server.properties
```

SOLO UNA VOLTA
```
cd Desktop\kafka
bin\windows\kafka-topics.bat --create     --bootstrap-server localhost:9092     --replication-factor 1     --partitions 1     --topic streams-plaintext-input
bin\windows\kafka-topics.bat --create     --bootstrap-server localhost:9092     --replication-factor 1     --partitions 1     --topic streams-wordcount-output     --config cleanup.policy=compact
```

IN UN TERMINALE DISPONIBILE
```
title wordcount
cd Desktop\WordCountToDB
mvn clean package
mvn exec:java -Dexec.mainClass=myapps.WordCount
```

IN UN NUOVO TERMINALE
```
title consumer
cd Desktop\kafka
bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092     --topic streams-wordcount-output     --from-beginning     --property print.key=true     --property print.value=true  --property value.deserializer=org.apache.kafka.common.serialization.LongDeserializer
```

IN UN NUOVO TERMINALE
```
title producer
cd Desktop\kafka
bin\windows\kafka-console-producer.bat --broker-list localhost:9092 --topic streams-plaintext-input
```
### Utilizzare l'applicativo
scrivere input nel terminale `producer` (ultimo terminale aperto)  
aspettare che il `consumer` produca output o che `wordcount` dia output (penultimo terminale)  
chiudere `wordcount` (ctrl+C)  (terzultimo terminale)
si possono verificare le scritture nel database H2  

### Esplorare il database
**ATTENZIONE:  
In questo esempio non è possibile aprire _simultaneamente_ il database  
in questa demo, per un corretto funzionamento il database può essere aperto  
o dall'applicativo o dal browser, _ma non da entrambi contemporaneamente_**

Aprire il DB con H2 Console  
Di seguito le informazioni necessarie per l'accesso  
JDBC Url: jdbc:h2:~/wordCount  
(il file sul vostro pc è `C:\Users\<USERNAME>\wordCount.h2.db`)  
Nome utente: sa  
password: nessuna password, lasciare il campo vuoto

### Esempio
1. Nel terminale `producer` scriviamo la frase:
```
progetto di esempio
```
2. Premiamo invio
3. Nel terminale `consumer` dovremmo eventualmente ricevere (tempo di aggiornamento 30 secondi) un output del genere
```
progetto 1
di 1
esempio 1
```
4. Nel terminale `producer` scriviamo la frase
```
esempio
```
5. Premiamo invio
6. Nel terminale `consumer` dovrebbe aggiungersi un output del genere
```
esempio 2
```
7. Chiudere `wordcount` con control-c
8. E nel database dovremmo avere questi valori (esplorare il database ad esempio con H2 Console)
```
progetto 1
di 1
esempio 2
```
