package samples;


public class SsmlSaySample extends BaseSample {

	public void run() throws Exception {
		
		client.waitFor("offer");
		client.answer();
		//client.saySsml("<audio src=\"http://ccmixter.org/content/fluffy/fluffy_-_You_Believed_It_Yourself_4.mp3\"></audio>Hello world");
		client.outputSsml("<speak xmlns=\"http://www.w3.org/2001/10/synthesis\" version=\"1.0\" xml:lang=\"en-US\"><voice gender=\"male\">Hello!</voice></speak>");
		Thread.sleep(500000);
		client.hangup();
	}
	
	public static void main(String[] args) throws Exception {
		
		SsmlSaySample sample = new SsmlSaySample();
		sample.connect("localhost", "usera", "1");
		sample.run();
		sample.shutdown();
	}
}
