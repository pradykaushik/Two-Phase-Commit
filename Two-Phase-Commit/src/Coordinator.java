import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.thrift.TException;

public class Coordinator implements FileStore.Iface{

	private String hostname;
	private int port;
	private static final int TIME_OUT = 5000;
	private static final String LOG_FILE_PATH_STRING = "Coordinator.log";
	private static final String ERROR_FILE_PATH_STRING = "Coordinator_Error.log";
	private static Map<String, ServerInfo> serverInformationMap = new HashMap<String, ServerInfo>();
	private static Map<ServerInfo, Map<String, Map<Operation, OperationStatus>>> ongoingFileOperations = new HashMap<ServerInfo, Map<String, Map<Operation,OperationStatus>>>();
	private static Map<ServerInfo, Map<String, RFile>> filesInfoMap = new HashMap<ServerInfo, Map<String, RFile>>();
	private static Map<ServerInfo, Map<RFile, Map<Operation, OperationStatus>>> recoveryMap = new HashMap<ServerInfo, Map<RFile,Map<Operation,OperationStatus>>>();

	public Coordinator(String hostname, int port, File inputFile){
		this.hostname = hostname;
		this.port = port;
		initialize(inputFile);
	}

	private static void initialize(File inputFile){
		BufferedReader inputFileReader = null;
		try{
			inputFileReader = new BufferedReader(new FileReader(inputFile));
			String line = null;
			String[] lineValues;
			String hostname, ipAddress;
			int port;
			ServerInfo serverInfo = null;
			while((line = inputFileReader.readLine()) != null){
				lineValues = line.split("\\s+");
				hostname = lineValues[0];
				ipAddress = InetAddress.getByName(hostname).getHostAddress();
				port = (int)Integer.parseInt(lineValues[1]);
				serverInfo = new ServerInfo(hostname, ipAddress, port);
				filesInfoMap.put(serverInfo, new HashMap<String, RFile>());
				ongoingFileOperations.put(serverInfo, new HashMap<String, Map<Operation,OperationStatus>>());
				serverInformationMap.put(hostname, serverInfo);
			}
		}
		catch(FileNotFoundException fileNotFoundException){
			fileNotFoundException.printStackTrace();
		}
		catch(IOException ioException){
			System.out.println("Error in reading input file!");
		}
		catch(NumberFormatException numberFormatException){
			System.out.println("Error reading input file! Please provide a valid port number.");
		}
	}

	private static enum OperationStatus{
		LOCAL_COMMIT,
		GLOBAL_COMMIT,
		ABORT;
	}

	@Override
	public StatusReport deleteFile(String filenameToDelete) throws SystemException,
	TException {
		/*
		 * need to send a vote message to all the servers.
		 * if received all yes votes,
		 * 		multicast global-commit messages to all the servers.
		 * else or timedout before hand then,
		 * 		multicast global-abort messages to all the servers.
		 */
		synchronized(filenameToDelete){
			FileOperation fileOperation = new FileOperation(Operation.DELETE);
			RFile rFile = new RFile();
			RFileMetadata rFileMetadata = new RFileMetadata();
			rFileMetadata.setFilename(filenameToDelete);
			rFile.setMetadata(rFileMetadata);
			//retrieving votes fro all the servers for the current operation.
			Map<ServerInfo, StatusReport> voteCollectorMap = getVotes(fileOperation, rFile);
			/*
			 * check ig all the servers have voted.
			 * if not then we need to log the abort message and send an abort message to all the alive servers.
			 * 		we also need to record the abort message for the crashed server (will use this when server boots up).
			 * Otherwise, if any failed votes then send abort message to all after logging it.
			 * 		Else if all success votes then send commit message to all the servers.
			 */
			if(voteCollectorMap.size() < serverInformationMap.size() || voteCollectorMap.values().contains(new StatusReport(Status.FAILED))){
				//logging abort message
				log(OperationStatus.ABORT, fileOperation, filenameToDelete);
				for(ServerInfo serverInfo : voteCollectorMap.keySet()){
					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.DELETE, OperationStatus.ABORT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInfo, fileOperationsMap);

					if(ongoingFileOperations.get(serverInfo).containsKey(filenameToDelete)){
						ongoingFileOperations.get(serverInfo).get(filenameToDelete).put(Operation.DELETE, OperationStatus.ABORT);
					}
					else{
						HashMap<Operation, OperationStatus> hashMap = new HashMap<Operation, OperationStatus>();
						HashMap<String, HashMap<Operation, OperationStatus>> perServerOngoingOperationMap = new HashMap<String, HashMap<Operation,OperationStatus>>();
						hashMap.put(Operation.DELETE, OperationStatus.ABORT);
						perServerOngoingOperationMap.put(filenameToDelete, hashMap);
						ongoingFileOperations.get(serverInfo).putAll(perServerOngoingOperationMap);
					}
					StatusReport statusReport = sendAbort(fileOperation, rFile, serverInfo.getHostname(), serverInfo.getPort(), serverInfo);
					if(statusReport != null){
						ongoingFileOperations.get(serverInfo).get(filenameToDelete).remove(Operation.DELETE);
						//removing corresponding entry from recoveryMap as the server has taken care of it.
						recoveryMap.get(serverInfo).get(rFile).remove(fileOperation.getOperation());
					}
				}
			}
			else{
				//need to log global-commit
				log(OperationStatus.GLOBAL_COMMIT, fileOperation, filenameToDelete);
				for(Map.Entry<String, ServerInfo> serverInformationMapEntry : serverInformationMap.entrySet()){

					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.DELETE, OperationStatus.GLOBAL_COMMIT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInformationMapEntry.getValue(), fileOperationsMap);

					Object commmitedValue = sendCommit(new FileOperation(Operation.DELETE), rFile, serverInformationMapEntry.getKey(), serverInformationMapEntry.getValue().getPort(), serverInformationMapEntry.getValue());
					if(commmitedValue != null){
						if(ongoingFileOperations.containsKey(serverInformationMapEntry.getValue()) && ongoingFileOperations.get(serverInformationMapEntry.getValue()).containsKey(filenameToDelete)){
							ongoingFileOperations.get(serverInformationMapEntry.getValue()).get(filenameToDelete).remove(Operation.DELETE);
						}
						//removing corresponding entry from recovery map as the server has taken care of it.
						recoveryMap.get(serverInformationMapEntry.getValue()).get(rFile).remove(fileOperation.getOperation());
						if(commmitedValue instanceof StatusReport){
							return (StatusReport)commmitedValue;
						}
					}
				}
			}
		}
		return null;
	}



	@Override
	public StatusReport writeFile(RFile rFile) throws SystemException,
	TException {
		/*
		 * need to send a vote message to all the servers
		 * if received all yes votes,
		 * 		multicast global-commit messages to all the servers.
		 * else or timedout before hand then,
		 * 		multicast global-abort messages to all the servers.
		 */
		synchronized (rFile) {
			String filename = rFile.getMetadata().getFilename();
			FileOperation fileOperation = new FileOperation(Operation.WRITE);
			//retrieving votes from all the servers for the current operation.
			Map<ServerInfo, StatusReport> voteCollectorMap = getVotes(fileOperation, rFile);
			/*
			 * check if all the servers have voted.
			 * If not then we need to log the abort message and send an abort message to all the alive servers.
			 * 		we also need to record the abort message for the crashed server (will use this when the server boots up).
			 * Otherwise, if any failed votes then send abort message to all after logging it.
			 * 		Else if all success votes then send commit message to all servers.
			 */
			if(voteCollectorMap.size() < serverInformationMap.size() || voteCollectorMap.values().contains(new StatusReport(Status.FAILED))){
				//logging abort message
				log(OperationStatus.ABORT, fileOperation, filename);
				for(ServerInfo serverInfo : voteCollectorMap.keySet()){

					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.WRITE, OperationStatus.ABORT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInfo, fileOperationsMap);

					if(ongoingFileOperations.get(serverInfo).containsKey(filename)){
						ongoingFileOperations.get(serverInfo).get(filename).put(Operation.WRITE, OperationStatus.ABORT);
					}
					else{
						HashMap<Operation, OperationStatus> hashMap = new HashMap<Operation, OperationStatus>();
						HashMap<String, HashMap<Operation, OperationStatus>> perServerOngoingOperationMap = new HashMap<String, HashMap<Operation,OperationStatus>>();
						hashMap.put(Operation.WRITE, OperationStatus.ABORT);
						perServerOngoingOperationMap.put(filename, hashMap);
						ongoingFileOperations.get(serverInfo).putAll(perServerOngoingOperationMap);
					}
					StatusReport statusReport = sendAbort(fileOperation, rFile, serverInfo.getHostname(), serverInfo.getPort(), serverInfo);
					if(statusReport != null){
						//removing corresponding entry from recovery map as the server has taken care of it.
						recoveryMap.get(serverInfo).get(rFile).remove(fileOperation.getOperation());
						ongoingFileOperations.get(serverInfo).get(filename).remove(Operation.WRITE);
					}
				}
			}
			else{
				//need to log global-commit
				log(OperationStatus.GLOBAL_COMMIT, fileOperation, filename);
				for(Map.Entry<String, ServerInfo> serverInformationMapEntry : serverInformationMap.entrySet()){

					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.WRITE, OperationStatus.GLOBAL_COMMIT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInformationMapEntry.getValue(), fileOperationsMap);

					Object commmitedValue = sendCommit(new FileOperation(Operation.WRITE), rFile, serverInformationMapEntry.getKey(), serverInformationMapEntry.getValue().getPort(), serverInformationMapEntry.getValue());
					if(commmitedValue != null){
						//removing corresponding entry from recovery map as the server has taken care of it.
						recoveryMap.get(serverInformationMapEntry.getValue()).get(rFile).remove(fileOperation.getOperation());
						if(commmitedValue instanceof StatusReport){
							ongoingFileOperations.get(serverInformationMapEntry.getValue()).get(filename).remove(Operation.WRITE);
							return (StatusReport)commmitedValue;
						}
					}
				}
			}
		}
		return null;
	}

	@Override
	public RFile readFile(String filename) throws SystemException, TException {
		/*
		 * need to send a vote message to all the servers.
		 * if received all yes votes,
		 * 		multicast global-commit messages to all the servers.
		 * else  or timedout before hand then
		 * 		multicast global-abort messages to all the servers.
		 */
		synchronized (filename) {
			RFile rFile = new RFile();
			RFileMetadata rFileMetadata = new RFileMetadata();
			rFileMetadata.setFilename(filename);
			rFile.setMetadata(rFileMetadata);
			FileOperation fileOperation = new FileOperation(Operation.READ);
			//retrieving votes from all the servers for the current operation
			Map<ServerInfo, StatusReport> voteCollectorMap = getVotes(fileOperation, rFile);
			/*
			 * check if all the servers have voted.
			 * If not then we need to log the abort and send an abort message to all the alive servers.
			 * 		we also need to record the abort message for the crashed server (will use this when server boots up).
			 * Otherwise, if any failed votes then send abort message to all
			 * 			Else if all success votes then send commit message to all servers.
			 */
			if(voteCollectorMap.size() < serverInformationMap.size() || voteCollectorMap.values().contains(new StatusReport(Status.FAILED))){
				//logging abort message
				log(OperationStatus.ABORT, fileOperation, filename);
				for(ServerInfo serverInfo : voteCollectorMap.keySet()){

					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.READ, OperationStatus.ABORT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInfo, fileOperationsMap);

					if(ongoingFileOperations.get(serverInfo).containsKey(filename)){
						ongoingFileOperations.get(serverInfo).get(filename).put(Operation.READ, OperationStatus.ABORT);
					}
					else{
						HashMap<Operation, OperationStatus> hashMap = new HashMap<Operation, OperationStatus>();
						HashMap<String, HashMap<Operation, OperationStatus>> perServerOngoingOperationsMap = new HashMap<String, HashMap<Operation,OperationStatus>>();
						hashMap.put(Operation.READ, OperationStatus.ABORT);
						perServerOngoingOperationsMap.put(filename,hashMap);
						ongoingFileOperations.get(serverInfo).putAll(perServerOngoingOperationsMap);
					}
					StatusReport statusReport = sendAbort(fileOperation, rFile, serverInfo.getHostname(), serverInfo.getPort(), serverInfo);
					if(statusReport != null){
						//removing corresponding entry from recovery map as the server has taken care of it.
						recoveryMap.get(serverInfo).get(rFile).remove(fileOperation.getOperation());
						ongoingFileOperations.get(serverInfo).get(filename).remove(Operation.READ);
					}
				}
			}
			else{
				//need to log global-commit
				log(OperationStatus.GLOBAL_COMMIT, fileOperation, filename);
				for(Map.Entry<String, ServerInfo> serverInformationMapEntry : serverInformationMap.entrySet()){

					//adding corresponding entry in recoveryMap
					Map<Operation, OperationStatus> operationMap = new HashMap<Operation, Coordinator.OperationStatus>();
					Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = new HashMap<RFile, Map<Operation,OperationStatus>>();
					operationMap.put(Operation.READ, OperationStatus.GLOBAL_COMMIT);
					fileOperationsMap.put(rFile, operationMap);
					recoveryMap.put(serverInformationMapEntry.getValue(), fileOperationsMap);

					Object commmitedValue = sendCommit(new FileOperation(Operation.READ), rFile, serverInformationMapEntry.getKey(), serverInformationMapEntry.getValue().getPort(), serverInformationMapEntry.getValue());
					if(commmitedValue != null){
						//removing corresponding entry from recovery map as they server has taken care of it.
						recoveryMap.get(serverInformationMapEntry.getValue()).get(rFile).remove(fileOperation.getOperation());
						if(commmitedValue instanceof RFile){
							ongoingFileOperations.get(serverInformationMapEntry.getValue()).get(filename).remove(Operation.READ);
							return (RFile)commmitedValue;
						}
					}

				}
			}
		}
		return null;
	}

	private synchronized Map<ServerInfo, StatusReport> getVotes(FileOperation fileOperation, RFile rFile){
		long startTime = new Date().getTime();
		Map<ServerInfo, StatusReport> voteCollectorMap = new HashMap<ServerInfo, StatusReport>();
		try{
			FileStore.Client server = null;
			StatusReport statusReport = null;
			for(Map.Entry<String, ServerInfo> serverInformationMapEntry : serverInformationMap.entrySet()){
				while((new Date().getTime() - startTime) < TIME_OUT){
					if((new Date().getTime() - startTime) >= TIME_OUT){
						throw new SystemException();
					}
					server = ServerFetcher.getServer(serverInformationMapEntry.getKey(), serverInformationMapEntry.getValue().getPort());
					if(server != null){
						break;
					}
				}
				statusReport = server.doVote(rFile, fileOperation);
				voteCollectorMap.put(serverInformationMapEntry.getValue(), statusReport);
			}
		}
		catch(SystemException systemException){
			return voteCollectorMap;
		}
		catch(TException tException){
			return voteCollectorMap;
		}
		if(voteCollectorMap.values().contains(new StatusReport(Status.FAILED))){
			for(ServerInfo serverInfo : voteCollectorMap.keySet()){
				Map<String, Map<Operation, OperationStatus>> hashMap = new HashMap<String, Map<Operation,OperationStatus>>();
				HashMap<Operation, OperationStatus> hashMap2 = new HashMap<Operation, OperationStatus>();
				hashMap2.put(fileOperation.getOperation(), OperationStatus.ABORT);
				hashMap.put(rFile.getMetadata().getFilename(), hashMap2);
				ongoingFileOperations.put(serverInfo, hashMap);
			}
		}
		else{
			for(ServerInfo serverInfo : voteCollectorMap.keySet()){
				Map<String, Map<Operation, OperationStatus>> hashMap = new HashMap<String, Map<Operation,OperationStatus>>();
				HashMap<Operation, OperationStatus> hashMap2 = new HashMap<Operation, OperationStatus>();
				hashMap2.put(fileOperation.getOperation(), OperationStatus.GLOBAL_COMMIT);
				hashMap.put(rFile.getMetadata().getFilename(), hashMap2);
				ongoingFileOperations.put(serverInfo, hashMap);
			}
		}
		return voteCollectorMap;
	}

	private synchronized StatusReport sendAbort(FileOperation fileOperation, RFile rFile, String hostname, int port, ServerInfo serverInfo){
		/*
		 * send abort message to each server.
		 * upon receiving status report, remove corresponding entry from the ongoing file operations map.
		 * in-case server crashes then there will still remain an ongoing operation in its entry.
		 */
		FileStore.Client server = null;
		StatusReport statusReport = null;
		try{
			server = ServerFetcher.getServer(hostname, port);
			if(server != null){
				ongoingFileOperations.get(serverInfo).get(rFile.getMetadata().getFilename()).remove(fileOperation.getOperation());
				statusReport = server.doAbort(rFile, fileOperation);
			}
		}
		catch(SystemException systemException){
			systemException.printStackTrace();
		}
		catch(TException tException){
			tException.printStackTrace();
		}
		return statusReport;
	}

	private synchronized Object sendCommit(FileOperation fileOperation, RFile rFile, String hostname, int port, ServerInfo serverInfo) throws SystemException, TException{
		/*
		 * send commit message to each server.
		 * upon receiving status report, remove corresponding entry from the ongoing file operations map.
		 * in-case server crashes then there will still remain an ongoing operation in its entry.
		 */
		FileStore.Client server = null;
		Object returnValue = null;
		long currentTime = new Date().getTime();
		while((new Date().getTime() - currentTime) < TIME_OUT && server == null){
			if((new Date().getTime() - currentTime) >= TIME_OUT){
				return null;
			}
			server = ServerFetcher.getServer(hostname, port);
		}
		if(server != null){
			if(fileOperation.getOperation().equals(Operation.READ)){
				returnValue = server.doCommitRead(rFile.getMetadata().getFilename());
				ongoingFileOperations.get(serverInfo).get(rFile.getMetadata().getFilename()).remove(fileOperation.getOperation());
			}
			else if(fileOperation.getOperation().equals(Operation.WRITE)){
				if(!filesInfoMap.get(serverInfo).containsKey(rFile.getMetadata().getFilename())){
					HashMap<String, RFile> map = new HashMap<String, RFile>();
					map.put(rFile.getMetadata().getFilename(), rFile);
					filesInfoMap.get(serverInfo).putAll(map);
				}
				returnValue =  server.doCommitWrite(rFile);
				if(((StatusReport)returnValue).equals(new StatusReport(Status.SUCCESSFUL))){
					ongoingFileOperations.get(serverInfo).get(rFile.getMetadata().getFilename()).remove(fileOperation.getOperation());
				}
			}
			else{
				returnValue = server.doCommitDelete(rFile.getMetadata().getFilename());
				if(((StatusReport)returnValue).equals(new StatusReport(Status.SUCCESSFUL))){
					filesInfoMap.get(serverInfo).remove(rFile.getMetadata().getFilename());
					ongoingFileOperations.get(serverInfo).remove(rFile.getMetadata().getFilename());
				}

			}
		}
		return returnValue;
	}

	@Override
	public RecoveryInformation getRecoveryInformation(String filename,
			FileOperation fileOperation, String hostname, int port) throws SystemException, TException {

		String ipAddress = null;
		ServerInfo serverInfo = null;
		RecoveryInformation recoveryInformation = new RecoveryInformation();
		try{
			ipAddress = InetAddress.getByName(hostname).getHostAddress();
			serverInfo = new ServerInfo(hostname, ipAddress, port);
			if(!recoveryMap.containsKey(serverInfo)){
				return null;
			}
			else{
				Map<RFile, Map<Operation, OperationStatus>> fileOperationsMap = recoveryMap.get(serverInfo);
				for(Map.Entry<RFile, Map<Operation, OperationStatus>> entry : fileOperationsMap.entrySet()){
					RFile rFileEntry = entry.getKey();
					if(rFileEntry.getMetadata().getFilename().equals(filename)){
						if(!entry.getValue().containsKey(fileOperation.getOperation())){
							Status status = Status.FAILED;
							recoveryInformation.setRFile(rFileEntry);
							recoveryInformation.setStatus(status);
							return recoveryInformation;
						}
						else{
							if(entry.getValue().get(fileOperation.getOperation()).equals(OperationStatus.GLOBAL_COMMIT)){
								Status status = Status.SUCCESSFUL;
								recoveryInformation.setRFile(rFileEntry);
								recoveryInformation.setStatus(status);
								return recoveryInformation;
							}
							else{
								Status status = Status.FAILED;
								recoveryInformation.setRFile(rFileEntry);
								recoveryInformation.setStatus(status);
								return recoveryInformation;
							}
						}
					}
				}
			}
		}
		catch(UnknownHostException unknownHostException){
			unknownHostException.printStackTrace();
		}
		//if here then entry not present for file and hence abort
		Status status = Status.FAILED;
		recoveryInformation.setRFile(null);
		recoveryInformation.setStatus(status);
		return recoveryInformation;

	}

	private static synchronized void log(OperationStatus operationStatus, FileOperation fileOperation, String filename){
		/*
		 * logging the abort message.
		 */
		File logFile = null;
		FileOutputStream fileOutputStream = null;
		StringBuilder logBuilder = new StringBuilder("");
		try{
			logFile = new File(LOG_FILE_PATH_STRING);
			fileOutputStream = new FileOutputStream(logFile, true);

			logBuilder.append(operationStatus.toString());
			logBuilder.append(":");
			logBuilder.append(fileOperation.getOperation().toString());
			logBuilder.append(" ");
			logBuilder.append(filename);
			logBuilder.append("\n");

			fileOutputStream.write(logBuilder.toString().getBytes());
		}
		catch(FileNotFoundException fileNotFoundException){
			File errorFile = new File(ERROR_FILE_PATH_STRING);
			FileOutputStream fileOutputStream2 = null;
			try{
				fileOutputStream2 = new FileOutputStream(errorFile, true);
				fileOutputStream2.write(fileNotFoundException.getStackTrace().toString().getBytes());
			}
			catch(Exception e){}
			finally{
				try{
					if(fileOutputStream2 != null){
						fileOutputStream2.close();
					}
				}
				catch(IOException ioException){
					ioException.printStackTrace();
				}
			}
		}
		catch(IOException ioException){
			ioException.printStackTrace();
		}
		finally{
			try{
				if(fileOutputStream != null){
					fileOutputStream.close();
				}
			}
			catch(IOException ioException2){
				ioException2.printStackTrace();
			}
		}
	}

	@Override
	public StatusReport doVote(RFile rFile, FileOperation fileOperation)
			throws SystemException, TException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RFile doCommitRead(String filenameToRead) throws SystemException,
	TException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StatusReport doCommitWrite(RFile rFile) throws SystemException,
	TException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StatusReport doCommitDelete(String filenameToDelete)
			throws SystemException, TException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StatusReport doAbort(RFile rFile, FileOperation fileOperation)
			throws SystemException, TException {
		// TODO Auto-generated method stub
		return null;
	}

}
