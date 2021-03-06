package mapreduce;
//******************************************************************************
//File:    Worker.java
//Package: None
//Unit:    Distributed Programming Group Project
//******************************************************************************

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class parses command-line input in order to register client as a worker in a 
 * MapReduce architecture.  Once connected, the nodes send heartbeats to each other 
 * as a connected distributed system.  This node will then wait to be sent jobs 
 * by the Master node.  
 * 
 * 
 * @author rob mccartney
 */
public class Worker implements Runnable {

	protected String hostName = ""; 
	protected int id;
	protected int port = Utils.DEF_MASTER_PORT;
	protected Socket socket;
	protected OutputStream out;
	protected InputStream in;
	//stopped is used by multiple threads, must be synchronized
	protected boolean stopped = false;
	protected Job<?, ?> currentJob;
	protected WorkerP2P wP2P;
	protected String basePath;
	protected File baseDir;
	protected boolean client;
	// TODO job queue 
    
    /**
     * Constructor that makes a new worker and attempts to register with a Master.
     * Master node must be already running
     * 
     * @param args String[] from the command line
     */
    public Worker(String[] args, boolean client) {
    	if (args.length > 0)  
    		parseArgs(args);
    	try {
    		if (hostName.length() == 0) 
    			hostName = InetAddress.getLocalHost().getHostAddress();
    		socket = new Socket(hostName, port);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            id = in.read();  //first thing sent is worker ID
    		System.out.println("Worker " + id + ": " + socket);
            // using port + 1 for Wp2p ensures it is unique for multiple instances
            this.client = client;
            if (!client) {
            	wP2P = new WorkerP2P(Utils.BASE_WP2P_PORT+id, this); 
            	writeMaster(Utils.W2M_WP2P_PORT);
            	try { Thread.sleep(10); } catch (Exception e) {}
            	writeMaster(Utils.intToByteArray(wP2P.port)); 
            	basePath = Utils.basePath + File.separator + id;
            	baseDir = new File(basePath);
            	if (!baseDir.isDirectory())
            		baseDir.mkdirs();
            }
            new Thread(this).start();  //start a thread to read from the Master
		} catch (Exception e) {
			System.out.println("Cannot connect to the Master server at this time.");
			System.out.println("Did you specify the correct hostname and port of the server?");
		}
    }
    
    public void writeMaster(String arg, byte... barg) {
    	try {
    		byte[] barr = Utils.concat(arg.getBytes(), barg);
    		out.write(barr);
    		out.flush();
    	} catch (IOException e) {
    		System.err.println("Error writing to Master: closing connection.");
    		this.closeConnection();
    	}
    }
    
    public void writeMaster(String arg) {
    	try {
    		out.write(arg.getBytes());
    		out.flush();
    	} catch (IOException e) {
    		System.err.println("Error writing to Master: closing connection.");
    		this.closeConnection();
    	}
    }
    
    public void writeMaster(byte... arg) {
    	try {
    		out.write(arg);
    		out.flush();
    	} catch (IOException e) {
    		System.err.println("Error writing to Master: closing connection.");
    		this.closeConnection();
    	}
    }

    // this was messing up writing a single byte
    public void writeMaster(int arg) {
    	try {
    		out.write(arg);
    		out.flush();
    	} catch (IOException e) {
    		System.err.println("Error writing to Master: closing connection.");
    		this.closeConnection();
    	}
    }
   
    public void writeObjToMaster(final Object obj) {
    	try {
    		ObjectOutputStream objStream = new ObjectOutputStream(out);
    		objStream.writeObject(obj);
    		objStream.flush();
    	} catch (IOException e) {
    		System.err.printf("Error writing to Worker %d: closing connection%n", id);
    		closeConnection();
    	}				
    }
    
    public synchronized boolean isStopped() {
    	return stopped;
    }
    
    public String toString() {
    	return socket.toString();
    }

    public synchronized void closeConnection() {
    	stopped = true;
    	try {
    		if (currentJob != null) 
    			currentJob.stopExecution();
    		if (!client) {
    			Files.deleteIfExists(Paths.get(baseDir.getPath()));
    			wP2P.closeConnection();
    		}
    		socket.close();
    		in.close();
    		out.close();
    	} catch (IOException e) {} //ignore exceptions since you are quitting
    }
    
  //need to do add path to Classpath with reflection since the URLClassLoader.addURL(URL url) method is protected:
    public static URLClassLoader addPath(String s) throws Exception {
        File f = new File(s);
        URI u = f.toURI();
        URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class<URLClassLoader> urlClass = URLClassLoader.class;
        Method method = urlClass.getDeclaredMethod("addURL", new Class[]{URL.class});
        method.setAccessible(true);
        method.invoke(urlClassLoader, new Object[]{u.toURL()});
        return urlClassLoader;
    }
    
    private Mapper<?, ?> loadMRFile(String classfile){		
		try {	
			// need each worker to have its own directory in case it is running locally
			// this requires the classpath to be changed
			Class<?> myClass = addPath(basePath).
					loadClass(classfile.split("\\.")[0]); 
			Mapper<?, ?> mr = (Mapper<?, ?>) myClass.newInstance();
			// clean up the files you created
			Files.delete(Paths.get(basePath + File.separator + classfile));
			return mr;
		} catch (Exception e) {
			System.err.println("Exception loading or compiling the File: " + e);
			return null;
		}
	} 			
    
	private String receiveFile(){
		try {
			// the first thing sent will be the filename
			int f;
			String name = "";
			while( (f = in.read()) != '\n') {
				name += (char) f;
			}
			// now read the actual byte array
			byte[] mybytearray = new byte[1024];
			BufferedOutputStream bos = new BufferedOutputStream(
					new FileOutputStream(basePath + File.separator + name));
			int totalCount = 0;
			while(true) {
				int bytesRead = in.read(mybytearray, 0, mybytearray.length);
				totalCount += bytesRead;
				if (bytesRead <= 0) break;
				bos.write(mybytearray, 0, bytesRead);
				if (bytesRead < 1024) break;
			}
			System.out.printf("%s %d bytes downloaded%n", name, totalCount);
			bos.close();
			return name;
		} catch (IOException e) {
    		System.err.println("Error receiving file from Master: closing connection.");
    		this.closeConnection();
    		return null;
		}
	}
	
	/*Function to send filesList to Master
     * Path of default directory is in Utils
     */
    public void sendFilesList(File path){
    	if (path.isDirectory()) {
    		File[] filesList = path.listFiles();
    		writeMaster(filesList.length);
    		for(int i=0;i<filesList.length;i++)
    			writeMaster(filesList[i].getName());
    	}
    	else
    		writeMaster(0);
    }
    
    public void receive(int command) {

		switch(command) {
		case Utils.MR_QUIT:  //quit command
    		closeConnection();
    		break;
		case Utils.M2W_COORD_KEYS:	
			currentJob.receiveKeyAssignments(in);
			break;
		case Utils.M2W_BEGIN_REDUCE:
			currentJob.reduce();
			break;	
		case Utils.M2W_MR_UPLOAD:
			// TODO filesystem that can take in actual file names instead of using all data there
			System.out.print("Worker received new MR job: ");
			Mapper<?, ?> mr = loadMRFile(receiveFile());
			if (mr != null) {
				ArrayList<String> names = new ArrayList<String>(Arrays.asList(baseDir.list()));
				currentJob = new Job<>(this, mr, names);
				currentJob.begin();
			}
			break;	
		case Utils.M2W_FILE:
			System.out.print("Worker received new file: ");
			receiveFile();
			break;
		case Utils.M2W_REQ_LIST:  // master is requesting file list
			writeMaster(Utils.M2W_REQ_LIST_OKAY);
			sendFilesList(baseDir);
			break;
		default:
			System.err.println("Unrecognized Worker command: " + command);
			this.closeConnection();
			break;
		}
    }
    
    public void run() {
    	int command;
    	while(!isStopped()) {
    		try {
    			if ((command = in.read()) != 0)
    				this.receive(command);
			} catch (IOException e) {
				if (isStopped()) // exception is expected when the connection is first closed
					return;
				System.err.println("Error in socket connection to Master: closing connection");
				this.closeConnection();
			}
    	}
    }
    
    /**
	 * This method parses any inputs for the port to use, and stores it into
	 * the instance variable prior to the constructor
	 * 
	 * @param args passed in on command line
	 */
	private void parseArgs(String args[]) {
		
		for (int i = 0; i < args.length; i ++) {	
			if (args[i].equals("-port")) 
				port = new Integer(args[++i]).intValue();
			else if (args[i].equals("-host")) 
				hostName = args[++i];
			else {
				System.out.println("Correct usage: java Worker [-host <hostName>] [-p <portnumber>] [");
				System.out.println("\t-host: override localhost to set the host to <hostName>.");
				System.out.println("\t-port: override default port 40001 to <port>.  "
						+ "\n\t<host> and <port> must match the Master Server's.");
				System.exit(1);
			}
		}
	}
	
	/**
	 * @param args for hostname or port to not be default
	 * @throws RemoteException 
	 */
	public static void main(String[] args) {
		new Worker(args, false);
	}
}

