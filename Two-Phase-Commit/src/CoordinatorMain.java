import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

public class CoordinatorMain {

	public static void main(String[] args) {

		int port = 0;
		if(args.length != 2)
			System.out.println("Wrong input! Input format : ./controller.sh <port> <input file>");
		else{
			try{
				port = (int)(Integer.valueOf(args[0]));
				String inputFileString = args[1];
				Path path = Paths.get(inputFileString);
				if(!Files.exists(path)){
					SystemException systemException = new FileNotPresentException();
					systemException.setMessage("Input file does not exist! Please provide a valid input file.");
					throw systemException;
				}
				File inputFile = new File(inputFileString);
				String hostname = InetAddress.getLocalHost().getHostName();
				startServer(new FileStore.Processor<Coordinator>(new Coordinator(hostname, port, inputFile)), (int)port);
			}
			catch(NumberFormatException e){
				System.out.println("Port number invalid! Port number should be an integer between 0 and 65535");
			}
			catch(UnknownHostException unknownHostException){
				unknownHostException.printStackTrace();
			}
			catch(SystemException fileNotPresentException){
				System.out.println(fileNotPresentException.getMessage());
			}
		}
	}

	public static void startServer(FileStore.Processor<Coordinator> coordinator, int port){

		TServerTransport tServerTransport = null;
		TServer tServer = null;

		try{

			tServerTransport = new TServerSocket(port);
			tServer = new TThreadPoolServer(new TThreadPoolServer.Args(tServerTransport).processor(coordinator));

			System.out.println("Starting coordinator...");
			tServer.serve();
		}
		catch(TTransportException e){
			e.printStackTrace();
		}
	}
}
