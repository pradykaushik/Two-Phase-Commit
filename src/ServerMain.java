import java.net.InetAddress;
import java.net.UnknownHostException;
import java.lang.NumberFormatException;

import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

public class ServerMain {

	public static void main(String[] args) {
		if(args.length < 2){
			System.out.println("Invalid input! input format : ./participant <participant name> <port number>");
			System.exit(0);
		}
		try {
			String ipAddress = InetAddress.getLocalHost().getHostAddress();
			String name = args[0];
			int port = Integer.valueOf(args[1]);
			startServer(new FileStore.Processor<FileStoreServer>(FileStoreServer.getInstance(name, ipAddress, port)), name, port);
		}
		catch(NumberFormatException nfe){
			System.out.println("Invalid port number! Please provide a valid port number in the range [0,65535].");
			System.exit(0);
		}
		catch (UnknownHostException e) {
			e.printStackTrace();
		}
		catch(SystemException se){
			System.out.println(se.getMessage());
		}
	}

	public static void startServer(FileStore.Processor<FileStoreServer> fileStoreHandler, String hostname, int port){

		TServerTransport serverTransport = null;
		TServer server = null;

		try{
			serverTransport = new TServerSocket(port);
			server = new TThreadPoolServer(new TThreadPoolServer.Args(serverTransport).processor(fileStoreHandler));

			System.out.println("starting File Server : "+hostname+" on port : "+port+"...");
			server.serve();
		}
		catch(TTransportException te){
			te.printStackTrace();
		}
	}
}
