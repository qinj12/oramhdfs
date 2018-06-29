/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.loadGenerator;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Options.CreateOpts;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.google.common.base.Preconditions;

/** The load generator is a tool for testing NameNode behavior under
 * different client loads. Note there is a subclass of this clas that lets 
 * you run a the load generator as a MapReduce job (see LoadGeneratorMR in the 
 * MapReduce project.
 * 
 * The loadGenerator allows the user to generate different mixes of read, write,
 * and list requests by specifying the probabilities of read and
 * write. The user controls the intensity of the load by
 * adjusting parameters for the number of worker threads and the delay
 * between operations. While load generators are running, the user
 * can profile and monitor the running of the NameNode. When a load
 * generator exits, it print some NameNode statistics like the average
 * execution time of each kind of operations and the NameNode
 * throughput.
 *
 * The program can run in one of two forms. As a regular single process command
 * that runs multiple threads to generate load on the NN or as a Map Reduce
 * program that runs multiple (multi-threaded) map tasks that generate load
 * on the NN; the results summary is generated by a single reduce task.
 * 
 * 
 * The user may either specify constant duration, read and write 
 * probabilities via the command line, or may specify a text file
 * that acts as a script of which read and write probabilities to
 * use for specified durations. If no duration is specified the program
 * runs till killed (duration required if run as MapReduce).
 * 
 * The script takes the form of lines of duration in seconds, read
 * probability and write probability, each separated by white space.
 * Blank lines and lines starting with # (comments) are ignored. If load
 * generator is run as a MapReduce program then the script file needs to be
 * accessible on the the Map task as a HDFS file.
 * 
 * After command line argument parsing and data initialization,
 * the load generator spawns the number of worker threads 
 * as specified by the user.
 * Each thread sends a stream of requests to the NameNode.
 * For each iteration, it first decides if it is going to read a file,
 * create a file, or listing a directory following the read and write 
 * probabilities specified by the user.
 * When reading, it randomly picks a file in the test space and reads
 * the entire file. When writing, it randomly picks a directory in the
 * test space and creates a file whose name consists of the current 
 * machine's host name and the thread id. The length of the file
 * follows Gaussian distribution with an average size of 2 blocks and
 * the standard deviation of 1 block. The new file is filled with 'a'.
 * Immediately after the file creation completes, the file is deleted
 * from the test space.
 * While listing, it randomly picks a directory in the test space and
 * list the directory content.
 * Between two consecutive operations, the thread pauses for a random
 * amount of time in the range of [0, maxDelayBetweenOps] 
 * if the specified max delay is not zero.
 * All threads are stopped when the specified elapsed time has passed 
 * in command-line execution, or all the lines of script have been 
 * executed, if using a script.
 * Before exiting, the program prints the average execution for 
 * each kind of NameNode operations, and the number of requests
 * served by the NameNode.
 *
 * The synopsis of the command is
 * java LoadGenerator
 *   -readProbability <read probability>: read probability [0, 1]
 *                                        with a default value of 0.3333. 
 *   -writeProbability <write probability>: write probability [0, 1]
 *                                         with a default value of 0.3333.
 *   -root <root>: test space with a default value of /testLoadSpace
 *   -maxDelayBetweenOps <maxDelayBetweenOpsInMillis>: 
 *      Max delay in the unit of milliseconds between two operations with a 
 *      default value of 0 indicating no delay.
 *   -numOfThreads <numOfThreads>: 
 *      number of threads to spawn with a default value of 200.
 *   -elapsedTime <elapsedTimeInSecs>: 
 *      the elapsed time of program with a default value of 0 
 *      indicating running forever
 *   -startTime <startTimeInMillis> : when the threads start to run.
 *   -scriptFile <file name>: text file to parse for scripted operation
 */
public class LoadGenerator extends Configured implements Tool {
  public static final Log LOG = LogFactory.getLog(LoadGenerator.class);
  
  private volatile static boolean shouldRun = true;
  protected static Path root = DataGenerator.DEFAULT_ROOT;
  private static FileContext fc;
  protected static int maxDelayBetweenOps = 0;
  protected static int numOfThreads = 200;
  protected static long [] durations = {0};
  protected static double [] readProbs = {0.3333};
  protected static double [] writeProbs = {0.3333};
  private static volatile int currentIndex = 0;
  protected static long totalTime = 0;
  protected static long startTime = Time.now()+10000;
  final static private int BLOCK_SIZE = 10;
  private static ArrayList<String> files = new ArrayList<String>();  // a table of file names
  private static ArrayList<String> dirs = new ArrayList<String>(); // a table of directory names
  protected static Random r = null;
  protected static long seed = 0;
  protected static String scriptFile = null;
  protected static final String FLAGFILE_DEFAULT = "/tmp/flagFile";
  protected static Path flagFile = new Path(FLAGFILE_DEFAULT);
  protected String hostname;
  final private static String USAGE_CMD = "java LoadGenerator\n";
  final protected static String USAGE_ARGS = 
	  "-readProbability <read probability>\n" +
      "-writeProbability <write probability>\n" +
      "-root <root>\n" +
      "-maxDelayBetweenOps <maxDelayBetweenOpsInMillis>\n" +
      "-numOfThreads <numOfThreads>\n" +
      "-elapsedTime <elapsedTimeInSecs>\n" +
      "-startTime <startTimeInMillis>\n" +
      "-scriptFile <filename>\n" +
      "-flagFile <filename>";
  final private static String USAGE = USAGE_CMD + USAGE_ARGS;
  

  


  private final byte[] WRITE_CONTENTS = new byte[4096];

  private static final int ERR_TEST_FAILED = 2;

  /** Constructor */
  public LoadGenerator() throws IOException, UnknownHostException {
    InetAddress addr = InetAddress.getLocalHost();
    hostname = addr.getHostName();
    Arrays.fill(WRITE_CONTENTS, (byte) 'a');
  }
  
  public LoadGenerator(Configuration conf) throws IOException, UnknownHostException {
    this();
    setConf(conf);
  }

  protected final static int OPEN = 0;
  protected final static int LIST = 1;
  protected final static int CREATE = 2;
  protected final static int WRITE_CLOSE = 3;
  protected final static int DELETE = 4;
  protected final static int TOTAL_OP_TYPES =5;
  protected static long [] executionTime = new long[TOTAL_OP_TYPES];
  protected static long [] numOfOps = new long[TOTAL_OP_TYPES];
  protected static long totalOps = 0; // across all of types
  
  /** A thread sends a stream of requests to the NameNode.
   * At each iteration, it first decides if it is going to read a file,
   * create a file, or listing a directory following the read
   * and write probabilities.
   * When reading, it randomly picks a file in the test space and reads
   * the entire file. When writing, it randomly picks a directory in the
   * test space and creates a file whose name consists of the current 
   * machine's host name and the thread id. The length of the file
   * follows Gaussian distribution with an average size of 2 blocks and
   * the standard deviation of 1 block. The new file is filled with 'a'.
   * Immediately after the file creation completes, the file is deleted
   * from the test space.
   * While listing, it randomly picks a directory in the test space and
   * list the directory content.
   * Between two consecutive operations, the thread pauses for a random
   * amount of time in the range of [0, maxDelayBetweenOps] 
   * if the specified max delay is not zero.
   * A thread runs for the specified elapsed time if the time isn't zero.
   * Otherwise, it runs forever.
   */
  private class DFSClientThread extends Thread {
    private int id;
    private long [] executionTime = new long[TOTAL_OP_TYPES];
    private long [] totalNumOfOps = new long[TOTAL_OP_TYPES];
    private byte[] buffer = new byte[1024];
    private boolean failed;

    private DFSClientThread(int id) {
      this.id = id;
    }
    
    /** Main loop for each thread
     * Each iteration decides what's the next operation and then pauses.
     */
    @Override
    public void run() {
      try {
        while (shouldRun) {
          nextOp();
          delay();
        }
      } catch (Exception ioe) {
        System.err.println(ioe.getLocalizedMessage());
        ioe.printStackTrace();
        failed = true;
      }
    }
    
    /** Let the thread pause for a random amount of time in the range of
     * [0, maxDelayBetweenOps] if the delay is not zero. Otherwise, no pause.
     */
    private void delay() throws InterruptedException {
      if (maxDelayBetweenOps>0) {
        int delay = r.nextInt(maxDelayBetweenOps);
        Thread.sleep(delay);
      }
    }
    
    /** Perform the next operation. 
     * 
     * Depending on the read and write probabilities, the next
     * operation could be either read, write, or list.
     */
    private void nextOp() throws IOException {
      double rn = r.nextDouble();
      int i = currentIndex;
      
      if(LOG.isDebugEnabled())
        LOG.debug("Thread " + this.id + " moving to index " + i);
      
      if (rn < readProbs[i]) {
        read();
      } else if (rn < readProbs[i] + writeProbs[i]) {
        write();
      } else {
        list();
      }
    }
    
    /** Read operation randomly picks a file in the test space and reads
     * the entire file */
    private void read() throws IOException {
      String fileName = files.get(r.nextInt(files.size()));
      long startTimestamp = Time.monotonicNow();
      InputStream in = fc.open(new Path(fileName));
      executionTime[OPEN] += (Time.monotonicNow() - startTimestamp);
      totalNumOfOps[OPEN]++;
      while (in.read(buffer) != -1) {}
      in.close();
    }
    
    /** The write operation randomly picks a directory in the
     * test space and creates a file whose name consists of the current 
     * machine's host name and the thread id. The length of the file
     * follows Gaussian distribution with an average size of 2 blocks and
     * the standard deviation of 1 block. The new file is filled with 'a'.
     * Immediately after the file creation completes, the file is deleted
     * from the test space.
     */
    private void write() throws IOException {
      String dirName = dirs.get(r.nextInt(dirs.size()));
      Path file = new Path(dirName, hostname+id);
      double fileSize = 0;
      while ((fileSize = r.nextGaussian()+2)<=0) {}
      genFile(file, (long)(fileSize*BLOCK_SIZE));
      long startTimestamp = Time.monotonicNow();
      fc.delete(file, true);
      executionTime[DELETE] += (Time.monotonicNow() - startTimestamp);
      totalNumOfOps[DELETE]++;
    }
    
    /** The list operation randomly picks a directory in the test space and
     * list the directory content.
     */
    private void list() throws IOException {
      String dirName = dirs.get(r.nextInt(dirs.size()));
      long startTimestamp = Time.monotonicNow();
      fc.listStatus(new Path(dirName));
      executionTime[LIST] += (Time.monotonicNow() - startTimestamp);
      totalNumOfOps[LIST]++;
    }

    /** Create a file with a length of <code>fileSize</code>.
     * The file is filled with 'a'.
     */
    private void genFile(Path file, long fileSize) throws IOException {
      long startTimestamp = Time.monotonicNow();
      FSDataOutputStream out = null;
      boolean isOutClosed = false;
      try {
        out = fc.create(file,
            EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE),
            CreateOpts.createParent(), CreateOpts.bufferSize(4096),
            CreateOpts.repFac((short) 3));
        executionTime[CREATE] += (Time.monotonicNow() - startTimestamp);
        numOfOps[CREATE]++;

        long i = fileSize;
        while (i > 0) {
          long s = Math.min(fileSize, WRITE_CONTENTS.length);
          out.write(WRITE_CONTENTS, 0, (int) s);
          i -= s;
        }

        startTime = Time.monotonicNow();
        out.close();
        executionTime[WRITE_CLOSE] += (Time.monotonicNow() - startTime);
        numOfOps[WRITE_CLOSE]++;
        isOutClosed = true;
      } finally {
        if (!isOutClosed && out != null) {
          out.close();
        }
      }
    }
  }
  
  /** Main function called by tool runner.
   * It first initializes data by parsing the command line arguments.
   * It then calls the loadGenerator
   */
  @Override
  public int run(String[] args) throws Exception {
    int exitCode = parseArgs(false, args);
    if (exitCode != 0) {
      return exitCode;
    }
    System.out.println("Running LoadGenerator against fileSystem: " + 
    FileContext.getFileContext().getDefaultFileSystem().getUri());
    exitCode = generateLoadOnNN();
    printResults(System.out);
    return exitCode;
  }
    
  boolean stopFileCreated() {
    try {
      fc.getFileStatus(flagFile);
    } catch (FileNotFoundException e) {
      return false;
    } catch (IOException e) {
      LOG.error("Got error when checking if file exists:" + flagFile, e);
    }
    LOG.info("Flag file was created. Stopping the test.");
    return true;
  }
  
 /**
  * This is the main function - run threads to generate load on NN
  * It starts the number of DFSClient threads as specified by
  * the user.
  * It stops all the threads when the specified elapsed time is passed.
  */
  protected int generateLoadOnNN() throws InterruptedException {
    int hostHashCode = hostname.hashCode();
    if (seed == 0) {
      r = new Random(System.currentTimeMillis()+hostHashCode);
    } else {
      r = new Random(seed+hostHashCode);
    }
    try {
      fc = FileContext.getFileContext(getConf());
    } catch (IOException ioe) {
      System.err.println("Can not initialize the file system: " + 
          ioe.getLocalizedMessage());
      return -1;
    }
    
    int status = initFileDirTables();
    if (status != 0) {
      return status;
    }
    barrier();
    
    DFSClientThread[] threads = new DFSClientThread[numOfThreads];
    for (int i=0; i<numOfThreads; i++) {
      threads[i] = new DFSClientThread(i); 
      threads[i].start();
    }
    
    if (durations[0] > 0) {
      if (durations.length == 1) {// There is a fixed run time
        while (shouldRun) {
          Thread.sleep(2000);
          totalTime += 2;
          if (totalTime >= durations[0] || stopFileCreated()) {
            shouldRun = false;
          }
        }
      } else {
        // script run

        while (shouldRun) {
          Thread.sleep(durations[currentIndex] * 1000);
          totalTime += durations[currentIndex];
          // Are we on the final line of the script?
          if ((currentIndex + 1) == durations.length || stopFileCreated()) {
            shouldRun = false;
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Moving to index " + currentIndex + ": r = "
                  + readProbs[currentIndex] + ", w = " + writeProbs
                  + " for duration " + durations[currentIndex]);
            }
            currentIndex++;
          }
        }
      }
    }
    
    if(LOG.isDebugEnabled()) {
      LOG.debug("Done with testing.  Waiting for threads to finish.");
    }
    
    boolean failed = false;
    for (DFSClientThread thread : threads) {
      thread.join();
      for (int i=0; i<TOTAL_OP_TYPES; i++) {
        executionTime[i] += thread.executionTime[i];
        numOfOps[i] += thread.totalNumOfOps[i];
      }
      failed = failed || thread.failed;
    }
    int exitCode = 0;
    if (failed) {
      exitCode = -ERR_TEST_FAILED;
    }

    totalOps = 0;
    for (int i=0; i<TOTAL_OP_TYPES; i++) {
      totalOps += numOfOps[i];
    }
    return exitCode;
  }
  
  protected static void printResults(PrintStream out) throws UnsupportedFileSystemException {
    out.println("Result of running LoadGenerator against fileSystem: " + 
    FileContext.getFileContext().getDefaultFileSystem().getUri());
    if (numOfOps[OPEN] != 0) {
      out.println("Average open execution time: " + 
          (double)executionTime[OPEN]/numOfOps[OPEN] + "ms");
    }
    if (numOfOps[LIST] != 0) {
      out.println("Average list execution time: " + 
          (double)executionTime[LIST]/numOfOps[LIST] + "ms");
    }
    if (numOfOps[DELETE] != 0) {
      out.println("Average deletion execution time: " + 
          (double)executionTime[DELETE]/numOfOps[DELETE] + "ms");
      out.println("Average create execution time: " + 
          (double)executionTime[CREATE]/numOfOps[CREATE] + "ms");
      out.println("Average write_close execution time: " + 
          (double)executionTime[WRITE_CLOSE]/numOfOps[WRITE_CLOSE] + "ms");
    }
    if (totalTime != 0) { 
      out.println("Average operations per second: " + 
          (double)totalOps/totalTime +"ops/s");
    }
    out.println();
  }
    

  /** Parse the command line arguments and initialize the data */
  protected int parseArgs(boolean runAsMapReduce, String[] args) throws IOException {
   try {
      for (int i = 0; i < args.length; i++) { // parse command line
        if (args[i].equals("-scriptFile")) {
          scriptFile = args[++i];
          if (durations[0] > 0)  {
            System.err.println("Can't specify elapsedTime and use script.");
            return -1;
          }
        } else if (args[i].equals("-readProbability")) {
          if (scriptFile != null) {
            System.err.println("Can't specify probabilities and use script.");
            return -1;
          }
          readProbs[0] = Double.parseDouble(args[++i]);
          if (readProbs[0] < 0 || readProbs[0] > 1) {
            System.err.println( 
                "The read probability must be [0, 1]: " + readProbs[0]);
            return -1;
          }
        } else if (args[i].equals("-writeProbability")) {
          if (scriptFile != null) {
            System.err.println("Can't specify probabilities and use script.");
            return -1;
          }
          writeProbs[0] = Double.parseDouble(args[++i]);
          if (writeProbs[0] < 0 || writeProbs[0] > 1) {
            System.err.println( 
                "The write probability must be [0, 1]: " + writeProbs[0]);
            return -1;
          }
        } else if (args[i].equals("-root")) {
          root = new Path(args[++i]);
        } else if (args[i].equals("-maxDelayBetweenOps")) {
          maxDelayBetweenOps = Integer.parseInt(args[++i]); // in milliseconds
        } else if (args[i].equals("-numOfThreads")) {
          numOfThreads = Integer.parseInt(args[++i]);
          if (numOfThreads <= 0) {
            System.err.println(
                "Number of threads must be positive: " + numOfThreads);
            return -1;
          }
        } else if (args[i].equals("-startTime")) {
          startTime = Long.parseLong(args[++i]);
        } else if (args[i].equals("-elapsedTime")) {
          if (scriptFile != null) {
            System.err.println("Can't specify elapsedTime and use script.");
            return -1;
          }
          durations[0] = Long.parseLong(args[++i]);
        } else if (args[i].equals("-seed")) {
          seed = Long.parseLong(args[++i]);
          r = new Random(seed);
        }  else if (args[i].equals("-flagFile")) {
          LOG.info("got flagFile:" + flagFile);
          flagFile = new Path(args[++i]);
        }else { 
          System.err.println(USAGE);
          ToolRunner.printGenericCommandUsage(System.err);
          return -1;
        }
      }
    } catch (NumberFormatException e) {
      System.err.println("Illegal parameter: " + e.getLocalizedMessage());
      System.err.println(USAGE);
      return -1;
    }
    
    // Load Script File if not MR; for MR scriptFile is loaded by Mapper
    if (!runAsMapReduce && scriptFile != null) { 
      if(loadScriptFile(scriptFile, true) == -1)
        return -1;
    }
    
    for(int i = 0; i < readProbs.length; i++) {
      if (readProbs[i] + writeProbs[i] <0 || readProbs[i]+ writeProbs[i] > 1) {
        System.err.println(
            "The sum of read probability and write probability must be [0, 1]: "
            + readProbs[i] + " " + writeProbs[i]);
        return -1;
      }
    }
    return 0;
  }

  private static void parseScriptLine(String line, ArrayList<Long> duration,
      ArrayList<Double> readProb, ArrayList<Double> writeProb) {
    String[] a = line.split("\\s");

    if (a.length != 3) {
      throw new IllegalArgumentException("Incorrect number of parameters: "
          + line);
    }

    try {
      long d = Long.parseLong(a[0]);
      double r = Double.parseDouble(a[1]);
      double w = Double.parseDouble(a[2]);

      Preconditions.checkArgument(d >= 0, "Invalid duration: " + d);
      Preconditions.checkArgument(0 <= r && r <= 1.0,
          "The read probability must be [0, 1]: " + r);
      Preconditions.checkArgument(0 <= w && w <= 1.0,
          "The read probability must be [0, 1]: " + w);

      readProb.add(r);
      duration.add(d);
      writeProb.add(w);
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Cannot parse: " + line);
    }
  }

  /**
   * Read a script file of the form: lines of text with duration in seconds,
   * read probability and write probability, separated by white space.
   * 
   * @param filename Script file
   * @return 0 if successful, -1 if not
   * @throws IOException if errors with file IO
   */
  protected static int loadScriptFile(String filename, boolean readLocally) throws IOException {
    
    FileContext fc;
    if (readLocally) { // read locally - program is run without MR
      fc = FileContext.getLocalFSFileContext();
    } else {
      fc = FileContext.getFileContext(); // use default file system
    }
    DataInputStream in = null;
    try {
      in = fc.open(new Path(filename));
    } catch (IOException e) {
      System.err.println("Unable to open scriptFile: " + filename);

      System.exit(-1);
    } 
    InputStreamReader inr = new InputStreamReader(in);
    
    BufferedReader br = new BufferedReader(inr);
    ArrayList<Long> duration  = new ArrayList<Long>();
    ArrayList<Double> readProb  = new ArrayList<Double>();
    ArrayList<Double> writeProb = new ArrayList<Double>();
    int lineNum = 0;
    
    String line;
    // Read script, parse values, build array of duration, read and write probs

    try {
      while ((line = br.readLine()) != null) {
        lineNum++;
        if (line.startsWith("#") || line.isEmpty()) // skip comments and blanks
          continue;

        parseScriptLine(line, duration, readProb, writeProb);
      }
    } catch (IllegalArgumentException e) {
      System.err.println("Line: " + lineNum + ", " + e.getMessage());
      return -1;
    } finally {
      IOUtils.cleanup(LOG, br);
    }
    
    // Copy vectors to arrays of values, to avoid autoboxing overhead later
    durations = new long[duration.size()];
    readProbs = new double[readProb.size()];
    writeProbs = new double[writeProb.size()];
    
    for(int i = 0; i < durations.length; i++) {
      durations[i] = duration.get(i);
      readProbs[i] = readProb.get(i);
      writeProbs[i] = writeProb.get(i);
    }
    
    if(durations[0] == 0)
      System.err.println("Initial duration set to 0.  " +
          		                             "Will loop until stopped manually.");
    
    return 0;
  }
  
  /** Create a table that contains all directories under root and
   * another table that contains all files under root.
   */
  private int initFileDirTables() {
    try {
      initFileDirTables(root);
    } catch (IOException e) {
      System.err.println(e.getLocalizedMessage());
      e.printStackTrace();
      return -1;
    }
    if (dirs.isEmpty()) {
      System.err.println("The test space " + root + " is empty");
      return -1;
    }
    if (files.isEmpty()) {
      System.err.println("The test space " + root + 
          " does not have any file");
      return -1;
    }
    return 0;
  }
  
  /** Create a table that contains all directories under the specified path and
   * another table that contains all files under the specified path and
   * whose name starts with "_file_".
   */
  private void initFileDirTables(Path path) throws IOException {
    FileStatus[] stats = fc.util().listStatus(path);

    for (FileStatus stat : stats) {
      if (stat.isDirectory()) {
        dirs.add(stat.getPath().toString());
        initFileDirTables(stat.getPath());
      } else {
        Path filePath = stat.getPath();
        if (filePath.getName().startsWith(StructureGenerator.FILE_NAME_PREFIX)) {
          files.add(filePath.toString());
        }
      }
    }
  }
  
  /** Returns when the current number of seconds from the epoch equals
   * the command line argument given by <code>-startTime</code>.
   * This allows multiple instances of this program, running on clock
   * synchronized nodes, to start at roughly the same time.
   */
  private static void barrier() {
    long sleepTime;
    while ((sleepTime = startTime - Time.now()) > 0) {
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException ex) {
      }
    }
  }
  
  /** Main program
   * 
   * @param args command line arguments
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new LoadGenerator(), args);
    System.exit(res);
  }
}
