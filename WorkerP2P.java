package mapreduce;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class' object is used by the worker to communicate with other worker peers.
 * This class' instance is always listening at the port specified for other W_P2P peers  
 *
 */
public class WorkerP2P extends Thread {
 
	protected ServerSocket workerServerSocket;
	protected Worker worker;
    protected int port; //This is the port used at src and dest. Since all W_P2P communications assume this port
    protected ExecutorService exec; //Thread pool to use to send data out asynchronously
    protected boolean stopRun = false;
    
    public WorkerP2P(int port, Worker worker) throws IOException {
    	this.exec = Executors.newCachedThreadPool();
    	this.port = port;   // == -1 ? Utils.DEF_WP2P_PORT: port;
    	workerServerSocket = new ServerSocket(this.port);
    	this.worker = worker;
    	this.setDaemon(true);
    	this.start();
    }
 
    /**
	 * Separate Thread: Listens for incoming messages
	 */
	public void run(){
		System.out.println("WorkerP2P Listener info: " + workerServerSocket);
		try {
			while(!stopRun){ //may be use !stopRun here
				Socket p2pSocket = workerServerSocket.accept();
				System.out.println("Accepted socket: " + p2pSocket);
				InputStream in = p2pSocket.getInputStream();
				OutputStream out = p2pSocket.getOutputStream();

				int cmd;
				if ((cmd = in.read()) != 0) { //commands are one byte
					switch(cmd){
					case Utils.W2W_KEY_TRANSFER:
						ObjectInputStream objInStream = new ObjectInputStream(in);
						Object[] objArr = (Object[]) objInStream.readObject();
						//objInStream.close();
						out.write(Utils.W2W_KEY_TRANSFER_OKAY); // to sync on all receives from a work
						out.flush();
						System.out.println(objArr[0] + " " + objArr[1]);
						worker.currentJob.receiveKVAndAggregate(objArr[0], objArr[1]);
						break;
					default:
						System.out.println("invalid command received at WP2P");
						break;
					}
				}
			}
		} catch (IOException | ClassNotFoundException e) {
			if(stopRun) return; //cuz if we intended to stop the server
			System.out.println("IOException in RecNotifThread:run()");
			System.out.println(e);
		}
	}
    
	/**
	 * Adhoc socket communication
	 * Send command, wait for command_okay, and then send Key,Value pair in an Object[] as JSON 
	 * @param key
	 * @param ls
	 * @param peerAddress
	 * @param port
	 */
	public <K,V> void send(final K key, final List<V> values, final String peerAddress, final int port){

		try {
			System.out.println("Sending!");
			Socket socket = new Socket(peerAddress, port);
			System.out.println(socket);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write(Utils.W2W_KEY_TRANSFER); 
			out.flush();
					
			ObjectOutputStream objOutStream = new ObjectOutputStream(out);
			objOutStream.writeObject(new Object[] {key, values});
			out.flush();
					
			if (in.read() != Utils.W2W_KEY_TRANSFER_OKAY)
				System.err.println("Invalid response received from Worker at " + peerAddress + "; port: " + port);
					
			out.close();
			in.close();
			socket.close();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Close the Listener socket 
	 */
	public void closeConnection(){
		stopRun = true;
		try {
			workerServerSocket.close();
			exec.shutdown();
		} catch (IOException e) {
			System.out.println("IOException in RecNotifThread:stopRecNotif()");
			System.out.println(e);
		}
	}
	
	//For testing standalone
	public static void main(String[] args) throws IOException{
		new WorkerP2P(-1, null);	
	}
}