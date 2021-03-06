package com.orientechnologies.orient.server.distributed;

/**
 * Test case to check the right management of distributed exception while a server is starting. Derived from the test provided by
 * Gino John for issue http://www.prjhub.com/#/issues/6449.
 *
 * 3 nodes, the test is started after the 1st node is up & running. The test is composed by multiple (8) parallel threads that
 * update the same records 20,000 times.
 * 
 * @author Luca Garulli
 */
public class HARemoteGraphTest extends HALocalGraphTest {
  @Override
  protected String getDatabaseURL(final ServerRun server) {
    return "remote:localhost:2424;localhost:2425;localhost:2426/" + getDatabaseName();
  }

  @Override
  public String getDatabaseName() {
    return "HARemoteGraphTest";
  }
}
