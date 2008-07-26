package org.jetbrains.plugins.ruby.testing.testunit.runner.states;

/**
 * @author Roman Chernyatchik
 *
 * Indicates that test is running.
 */
public class TestInProgressState extends AbstractState {
  //This state is common for all instances and doesn't contains
  //instance-specific information
  public static final TestInProgressState TEST = new TestInProgressState();

  protected TestInProgressState() {
  }

  public boolean isInProgress() {
    return true;
  }

  public boolean isDefect() {
    return false;
  }

  public boolean wasLaunched() {
    return true;
  }

  public boolean isFinal() {
    return false;
  }

  @Override
  public String toString() {
    //noinspection HardCodedStringLiteral
    return "TEST PROGRESS";
  }
}
