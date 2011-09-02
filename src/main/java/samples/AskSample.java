package samples;

import com.rayo.core.verb.AskCompleteEvent;

public class AskSample extends BaseSample {

	public void run() throws Exception {
		
		client.waitFor("offer");
		client.answer();
		client.ask("Welcome to Orlando Bank. Please enter your five digits number.","[4-5 DIGITS]");
		
		AskCompleteEvent complete = (AskCompleteEvent)client.waitFor("complete");
		System.out.println("Success: " + complete.isSuccess());

		Thread.sleep(500000);
		client.hangup();
	}
	
	public static void main(String[] args) throws Exception {
		
		AskSample sample = new AskSample();
		sample.connect("localhost", "user100", "1");
		sample.run();
		sample.shutdown();
	}
}
