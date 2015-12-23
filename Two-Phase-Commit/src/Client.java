import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Scanner;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

public class Client {

	private static Map<String,String> commandLineMap;
	private static String coordinatorHostname = "PK.local";
	private static int coordinatorPort = 12345;
	private static TJSONProtocol tjsonProtocol;

	static{
		tjsonProtocol = new TJSONProtocol(new TIOStreamTransport(System.out));
	}

	public static void main(String[] args) {
		System.out.println("starting client...");
		Scanner scanner = new Scanner(System.in);
		String userInput = null;
		String[] userInputComponents = null;
		while(true){
			System.out.println("Please enter the operation and the filename. Enter EXIT to terminate the program.");
			userInput = scanner.nextLine();
			userInputComponents = userInput.split("\\s+");
			if(userInputComponents.length != 2 && !userInputComponents[0].equalsIgnoreCase("exit")){
				System.out.println("Wrong input!");
				System.exit(0);
			}
			else{
				switch(userInputComponents[0].toLowerCase()){
				case "read" :
					String filenameToRead = userInputComponents[1];
					performRead(filenameToRead);
					break;
				case "write" :
					String filenameToWrite = userInputComponents[1];
					Path path = Paths.get(filenameToWrite);
					if(!Files.exists(path)){
						System.out.println("input file does not exist! Please provide valid input file.");
						System.exit(0);
					}
					else{
						File file = new File(filenameToWrite);
						BufferedReader bufferedReader = null;
						StringBuilder contentBuilder = new StringBuilder("");
						try{
							bufferedReader = new BufferedReader(new FileReader(file));
							String line = null;
							while((line = bufferedReader.readLine()) != null){
								contentBuilder.append(line+"\n");
							}
							contentBuilder.setLength(contentBuilder.length()-1);
							performWrite(filenameToWrite, contentBuilder.toString());
						}
						catch(IOException ioException){
							System.out.println("Error : Please try again.");
							System.exit(0);
						}
					}
					break;
				case "delete" :
					String filenameToDelete = userInputComponents[1];
					performDelete(filenameToDelete);
					break;
				case "exit" :
					System.exit(0);
					break;
				}
			}
		}
	}

	private static void performRead(String filename){

		FileStore.Client coordinator = ServerFetcher.getServer(coordinatorHostname, coordinatorPort);
		RFile rFile = null;
		RFileMetadata rFileMetadata = null;

		try{

			rFile = coordinator.readFile(filename);
			rFile.write(tjsonProtocol);
			System.out.println();
		}
		catch(SystemException se){

			System.out.println(se.getMessage());
		}
		catch(TException te){
			System.out.println(te.getMessage());
		}
	}

	private static void performWrite(String filename, String content){

		FileStore.Client coordinator = ServerFetcher.getServer(coordinatorHostname, coordinatorPort);
		RFile rFile = null;
		RFileMetadata rFileMetadata = null;
		StatusReport statusReport = null;

		try{

			rFile = new RFile();
			rFileMetadata = new RFileMetadata();

			//setting metadata
			rFileMetadata.setFilename(filename);

			//setting content and metadata to rFile
			rFile.setContent(content);
			rFile.setMetadata(rFileMetadata);

			//making rpc to write method
			statusReport = coordinator.writeFile(rFile);
			statusReport.write(tjsonProtocol);
			System.out.println();
		}
		catch(SystemException se){
			System.out.println(se.getMessage());
		}
		catch(TException te){
			System.out.println(te.getMessage());
		}
	}

	private static void performDelete(String filename){
		FileStore.Client coordinator = ServerFetcher.getServer(coordinatorHostname, coordinatorPort);
		StatusReport statusReport = null;
		try{
			statusReport = coordinator.deleteFile(filename);
			statusReport.write(tjsonProtocol);
			System.out.println();
		}
		catch(SystemException systemException){
			System.out.println(systemException.getMessage());
		}
		catch(TException tException){
			tException.printStackTrace();
		}
	}
}
