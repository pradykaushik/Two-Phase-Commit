LIB_PATH=lib/libthrift-0.9.2.jar:lib/slf4j-api-1.7.12.jar:lib/slf4j-simple-1.7.12.jar
all: clean
	mkdir bin
	javac -classpath $(LIB_PATH) -d bin src/Client.java src/Coordinator.java src/CoordinatorMain.java src/FileNotPresentException.java src/FileOperation.java src/FileStore.java src/FileStoreServer.java src/Operation.java src/RecoveryInformation.java src/RFile.java src/rFileMetadata.java src/ServerFetcher.java src/ServerInfo.java src/ServerMain.java src/Status.java src/StatusReport.java src/SystemException.java

clean:
	rm -rf bin *~
