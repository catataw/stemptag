package temporal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;

import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import temporal.rules.TIMEXRuleAnnotator;
import com.aliasi.chunk.BioTagChunkCodec;
import com.aliasi.chunk.CharLmHmmChunker;
import com.aliasi.chunk.Chunk;
import com.aliasi.chunk.Chunker;
import com.aliasi.chunk.TagChunkCodec;
import com.aliasi.corpus.Parser;
import com.aliasi.corpus.XValidatingObjectCorpus;
import com.aliasi.crf.ChainCrfChunker;
import com.aliasi.crf.ChainCrfFeatureExtractor;
import com.aliasi.hmm.HmmCharLmEstimator;
import com.aliasi.io.LogLevel;
import com.aliasi.io.Reporter;
import com.aliasi.io.Reporters;
import com.aliasi.stats.AnnealingSchedule;
import com.aliasi.stats.RegressionPrior;
import com.aliasi.tokenizer.IndoEuropeanTokenizerFactory;
import com.aliasi.tokenizer.TokenizerFactory;
import com.aliasi.util.AbstractExternalizable;

import weka.classifiers.Classifier;

public class TIMEXExperiment {

	private static String path = ".";

	public static void trainResolver(File in, File out) throws Exception {
		boolean crf = out.getAbsolutePath().endsWith(".crf");
		Parser parser = new TIMEXChunkParser();
		if (crf) {
			TokenizerFactory factory = new IndoEuropeanTokenizerFactory();
			boolean enforceConsistency = true, cacheFeatures = true, addIntercept = true, uninformativeIntercept = true;
			int minFeatureCount = 1, priorBlockSize = 3, minEpochs = 10, maxEpochs = 5000;
			double priorVariance = 4.0, initialLearningRate = 0.05, learningRateDecay = 0.995, minImprovement = 0.00001;
			TagChunkCodec tagChunkCodec = new BioTagChunkCodec(factory,	enforceConsistency);
			ChainCrfFeatureExtractor<String> featureExtractor = new CRFFeatureExtractor("models/POS/pos-en-general-brown.HiddenMarkovModel");
			RegressionPrior prior = RegressionPrior.gaussian(priorVariance,	uninformativeIntercept);
			AnnealingSchedule annealingSchedule = AnnealingSchedule.exponential(initialLearningRate, learningRateDecay);
			Reporter reporter = Reporters.stdOut().setLevel(LogLevel.ERROR);
			XValidatingObjectCorpus corpus = new XValidatingObjectCorpus(0);
			parser.setHandler(corpus);
			parser.parse(in);
			ChainCrfChunker chunker = ChainCrfChunker.estimate(corpus,
					tagChunkCodec, factory, featureExtractor, addIntercept,
					minFeatureCount, cacheFeatures, prior, priorBlockSize,
					annealingSchedule, minImprovement, minEpochs, maxEpochs,
					reporter);
			AbstractExternalizable.serializeTo(chunker, out);
		} else {
			int MAX_N_GRAM = 8, NUM_CHARS = 256;
			double LM_INTERPOLATION = MAX_N_GRAM;
			TokenizerFactory factory = new IndoEuropeanTokenizerFactory();
			HmmCharLmEstimator hmmEstimator = new HmmCharLmEstimator(MAX_N_GRAM, NUM_CHARS, LM_INTERPOLATION, true);
			CharLmHmmChunker chunkerEstimator = new CharLmHmmChunker(factory, hmmEstimator);
			parser.setHandler(chunkerEstimator);
			parser.parse(in);
			AbstractExternalizable.compileTo(chunkerEstimator, out);
		}
	}

	public static void testResolver(File model, String regressionModelFilePath, File data, PrintStream eval, PrintStream out) throws Exception {
		Chunker chunker = null;
		chunker = (Chunker) AbstractExternalizable.readObject(model);
		Classifier regressionModel = TIMEXRegressionDisambiguation.readModel(regressionModelFilePath);
		testResolver(chunker, regressionModel, data, eval, out);
	}

	public static void testResolver(Chunker chunker, Classifier regressionModel, File data,	PrintStream eval, PrintStream out) throws Exception {
		//Evaluate CRF model
	/*	ChunkerEvaluator evaluator = new ChunkerEvaluator(chunker);
		evaluator.setVerbose(true);
		Parser parser = new TIMEXChunkParser();
		parser.setHandler(evaluator);
		parser.parse(data);
		if (chunker instanceof TIMEXRuleAnnotator)
			eval.print("Rule-based model - ");
		else if (chunker instanceof ChainCrfChunker)
			eval.print("CRF model - ");
		else
			eval.print("HMM model - ");
		eval.println();
		eval.println(evaluator.evaluation().perTypeEvaluation("TIMEX2").precisionRecallEvaluation().toString());*/
		
		
		//Evaluate Regression model
		NormalizedChunkerEvaluator evaluatorRegression = new NormalizedChunkerEvaluator(new TIMEXMLAnnotator(chunker,regressionModel));
		evaluatorRegression.setVerbose(true);
		Parser parserRegression = new TIMEXChunkParser();
		parserRegression.setHandler(evaluatorRegression);
		parserRegression.parse(data);
		if (chunker instanceof TIMEXRuleAnnotator)
			eval.print("Rule-based model - ");
		else if (chunker instanceof ChainCrfChunker)
			eval.print("CRF model - ");
		else
			eval.print("HMM model - ");
		eval.println();
		eval.println(evaluatorRegression.evaluation().perTypeEvaluation("TIMEX2").precisionRecallEvaluation().toString());
		
		//Anota o corpus de acordo com o que o sistema da
		/*if (out != null)
			annotateData(data, regressionModel, out, chunker);*/
	}

	//Faz a anotacao do corpus de acordo com o que o sistema da
	public static void annotateData(File data, Classifier regressionModel, PrintStream out, Chunker chunker2)
	throws Exception {
		Chunker chunker = new TIMEXRuleDisambiguation(chunker2);
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder loader = factory.newDocumentBuilder();
		Document doc = loader.parse(new FileInputStream(data));
		javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
		 
		NodeList docs = (NodeList) xpath.compile("//doc").evaluate(doc,	XPathConstants.NODESET);
		out.println("<corpus>");
		//For each document
		for (int i = 0; i < docs.getLength(); i++) {
			CandidateCreation.candidatesMillisecondsSameDoc.clear();
    		CandidateCreation.candidatesIntervalsSameDoc.clear();
    		CandidateCreation.isFirstTimex = true;
		
			out.println("<doc>");
			NodeList paragraphs = (NodeList) xpath.compile("./p").evaluate(docs.item(i), XPathConstants.NODESET);
			//For each sentence
			for (int j = 0; j < paragraphs.getLength(); j++) {
				//Computes the document timestamp for each document
				if(paragraphs.item(j).hasAttributes()){
					CandidateCreation.docCreationTime = new DateTime(new String(paragraphs.item(j).getAttributes().item(0).getTextContent()));
					System.out.println("Data criacao documento: "+CandidateCreation.docCreationTime);
				}
				
				CandidateCreation.candidatesMillisecondsSameSentence.clear();
			    CandidateCreation.candidatesIntervalsSameSentence.clear();
				out.print("<p>");
				String txt = xpath.compile(".").evaluate(paragraphs.item(j));
				Chunk[] chunking = chunker.chunk(txt).chunkSet().toArray(new Chunk[0]);
				int lastPos = 0;
				//For each TIMEX
				for (int k = 0; k < chunking.length; k++) {
					NormalizedChunk timex = (NormalizedChunk)chunking[k];
					int start = timex.start();
					int end = timex.end();
					String chunkText = txt.substring(start,end);
	 	    		timex.setNormalized(TIMEXRuleDisambiguation.createCanonicalForm(chunkText));
	 	            System.out.println(chunkText+"#");
	 	            ArrayList<Interval> normalized = timex.getNormalizedSet();
	 	            out.print(txt.substring(lastPos, start));
					out.print("<TIMEX2");
					
	 	            if (normalized == null){
	 	        	    out.print(">");
						out.print(chunkText);
						out.print("</TIMEX2>");
	 	 	            lastPos = end;
	 	 	            CandidateCreation.isNull = false;
	 	            }
	 	            else{
	 	            	System.out.println("Numero candidatos: "+normalized.size());
	 	            	String bestCandidate = TIMEXRegressionDisambiguation.disambiguate(chunkText, normalized, regressionModel);
	 	            	timex.setNormalized(bestCandidate);
	 	            	CandidateCreation.granularityDuration = 0;
	 	            	CandidateCreation.numberCandidatesTimex = 0;
	 	            	out.print(" val=\"");
	 	            	out.print(bestCandidate+"\"");
	 	            	out.print(">");
						out.print(chunkText);
						out.print("</TIMEX2>");
						lastPos = end;
	 	            }
				}
				out.print(txt.substring(lastPos));
				out.println("</p>");
			}
			out.println("</doc>");
		}
		out.println("</corpus>");
	}

	public static void prepareCorpus ( String path, int percent ) throws IOException {
		PrintWriter test = new PrintWriter(new FileWriter(path+"/../timex-test.xml"));
		PrintWriter train = new PrintWriter(new FileWriter(path+"/../timex-train.xml"));
		File files[] = new File(path).listFiles();
		int split = (int)((double)files.length * ((double)percent / 100.0));
		System.out.println(split);
		 
		test.println("<corpus>");
		train.println("<corpus>");
		//See which corpus to use
		if(path.contains("ACE")){
			System.out.println("ACE_Corpus");
			
			}
		else if(path.contains("aquaint_timeml")){
			System.out.println("AQUAINT_TimeML_Corpus");
			
			for (int pos = 1 ; pos < files.length ; pos++ ) {
				PrintWriter out = ( pos < split ) ? train : test;
				BufferedReader input = new BufferedReader(new FileReader(files[pos]));
				String aux = null;
				String aux2 = " ";
				String _split = null;
				String _split2 = null;
				String[] str=null;
				out.println("<doc>");
				//Takes creation date from document name
				String[] splitDocName = files[pos].getName().split("\\.");
				String date = splitDocName[0].substring(3);
				String ano = date.substring(0, 4);
				String mes = date.substring(4, 6);
				String dia = date.substring(6);
				out.println("<p DOCCREATIONTIME=\""+ano+"-"+mes+"-"+dia+"\"></p>");
				
				Boolean _flag=false;
				Pattern _patern = null;
				Pattern _patern2 = null;
				if(files[pos].getName().contains("APW")){
					_patern = Pattern.compile("^\t.*");
					_patern2 = Pattern.compile("   [A-Z].*");
					_split = "\t";
					_split2= "   ";
					_flag=false;
				}
				else if ((files[pos].getName().contains("NYT"))){
					_patern = Pattern.compile("   [A-Z].*");
					_patern2 = Pattern.compile("   [A-Z].*");
					_split = "   ";
					_flag=false;
				}
				else if ((files[pos].getName().contains("XIE"))){
					_patern = Pattern.compile("^[A-Z]+.*");
					_patern2 = Pattern.compile("^[A-Z]+.*");
					_split = "��";
					_flag=true;
				}
				while ( (aux=input.readLine()) != null) { 
					if(_patern.matcher(aux).matches() | _patern2.matcher(aux).matches()) {
					aux = aux.replaceAll("<EVENT[^>]*>","");aux = aux.replaceAll("</EVENT>","");
					aux = aux.replaceAll("<SIGNAL[^>]*>","");aux = aux.replaceAll("</SIGNAL>","");
					aux = aux.replaceAll("<TIMEX3","<TIMEX2");
					aux = aux.replaceAll("</TIMEX3>","</TIMEX2>");
					aux = aux.replaceAll(" tid=\"[^\"]*\"","");
					aux = aux.replaceAll(" type=\"SET\""," set=\"YES\"");
					aux = aux.replaceAll(" temporalFunction=\"[^\"]*\"","");
					aux = aux.replaceAll(" functionInDocument=\"[^\"]*\"","");
					aux = aux.replaceAll(" anchorTimeID=\"[^\"]*\"","");
					aux = aux.replaceAll("beginPoint=\"[^\"]*\"","");
					aux = aux.replaceAll(" mod=\"[^\"]*\"","");
					aux = aux.replaceAll("value=","val=");
					aux = aux.replaceAll("endPoint=\"[^\"]*\"","");
					if (aux.contains("type=\"DURATION\"") || aux.contains("type=\"TIME\"") || aux.contains("type=\"DATE\""))
						aux = aux.replaceAll(" type=\"([^\"]*)\"","");
					
					aux = aux.replaceAll("freq=\"[^\"]*\"","");
					
					aux2= aux; break;} }
				while ( (aux=input.readLine()) != null ) {
					
					if(aux.trim().contains("</TimeML>")) {
						aux2 = aux2.replaceAll("\\(PROFILE.*","");
						str = aux2.split(_split);						
						
						if(str.length!=1){
							for (int j=0;j<str.length;j++){
								if(str[j].contains(".")){
									out.println("<p>"+str[j].trim()+"</p>");
								}
							}
						}
						else{
							str = aux2.split(_split2);
							for (int j=0;j<str.length;j++){
								if(str[j].contains("."))
									out.println("<p>"+str[j].trim()+"</p>");
							}
						}
					aux2 = " ";
					break;
					}
					
					aux = aux.replaceAll("<MAKEINSTANCE[^>]*/>","");
					aux = aux.replaceAll("<TLINK[^>]*/>","");
					aux = aux.replaceAll("<ALINK[^>]*/>","");
					aux = aux.replaceAll("<SLINK[^>]*/>","");
					aux = aux.replaceAll("<DOC>","");aux = aux.replaceAll("</DOC>","");
					aux = aux.replaceAll("<DOCNO>","");aux = aux.replaceAll("</DOCNO>","");
					aux = aux.replaceAll("<DATE_TIME>","");aux = aux.replaceAll("</DATE_TIME>","");
					aux = aux.replaceAll("<BODY>","");aux = aux.replaceAll("</BODY>","");
					aux = aux.replaceAll("<TEXT>","");aux = aux.replaceAll("</TEXT>","");
					aux = aux.replaceAll("<P>","");aux = aux.replaceAll("</P>","");
					aux = aux.replaceAll("<EVENT[^>]*>","");aux = aux.replaceAll("</EVENT>","");
					aux = aux.replaceAll("<SIGNAL[^>]*>","");aux = aux.replaceAll("</SIGNAL>","");
					aux = aux.replaceAll("<ANNOTATION[^>]*>","");aux = aux.replaceAll("</ANNOTATION>","");
					aux = aux.replaceAll("<TRAILER[^>]*>","");aux = aux.replaceAll("</TRAILER>","");
					aux = aux.replaceAll(".*&amp;QL;.*","");
					aux = aux.replaceAll(".*&amp;UR;.*","");
					aux = aux.replaceAll("&amp;LR;","");
					aux = aux.replaceAll("<TIMEX3","<TIMEX2");
					aux = aux.replaceAll("</TIMEX3>","</TIMEX2>");
					aux = aux.replaceAll(" tid=\"[^\"]*\"","");
					aux = aux.replaceAll(" type=\"SET\""," set=\"YES\"");
					aux = aux.replaceAll(" temporalFunction=\"[^\"]*\"","");
					aux = aux.replaceAll(" functionInDocument=\"[^\"]*\"","");
					aux = aux.replaceAll(" anchorTimeID=\"[^\"]*\"","");
					aux = aux.replaceAll("beginPoint=\"[^\"]*\"","");
					aux = aux.replaceAll(" mod=\"[^\"]*\"","");
					aux = aux.replaceAll("value=","val=");
					aux = aux.replaceAll("endPoint=\"[^\"]*\"","");
					if (aux.contains("type=\"DURATION\"") || aux.contains("type=\"TIME\"") || aux.contains("type=\"DATE\""))
						aux = aux.replaceAll(" type=\"([^\"]*)\"","");
								
					aux = aux.replaceAll("freq=\"[^\"]*\"","");
					
					if (!aux.equals(""))
						aux2 = aux2+" "+aux;
					else if (_flag)
						aux2 = aux2+"��";
					
				}
			aux2=" ";
			out.println("</doc>");
		}
		}

		else if(path.contains("TimeBank")){
			System.out.println("TimeBank_Corpus");
			
				for (int pos = 0 ; pos < files.length ; pos++ ) {
					PrintWriter out = ( pos < split ) ? train : test;
					BufferedReader input = new BufferedReader(new FileReader(files[pos]));
					Document doc;
					Node docno;
					String aux = null;
					String aux2 = " ";
					String[] str=null;
					out.println("<doc>");

					try {
						DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
						DocumentBuilder loader = factory.newDocumentBuilder();
						javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
						InputSource is = new InputSource();
						
						if (files[pos].getAbsolutePath().contains("ABC") || 
							files[pos].getAbsolutePath().contains("CNN") || 
							files[pos].getAbsolutePath().contains("ea9") || 
							files[pos].getAbsolutePath().contains("ea9") || 
							files[pos].getAbsolutePath().contains("ed9") ||
							files[pos].getAbsolutePath().contains("PRI") ||
							files[pos].getAbsolutePath().contains("VOA")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DOCNO>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
						}
						else if (files[pos].getAbsolutePath().contains("AP9")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<FILEID>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
						}
						else if (files[pos].getAbsolutePath().contains("APW") ||
								 files[pos].getAbsolutePath().contains("NYT")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DATE_TIME>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
						}
						else if (files[pos].getAbsolutePath().contains("SJMN")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DATELINE>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
							input = new BufferedReader(new FileReader(files[pos]));
						}
						else if (files[pos].getAbsolutePath().contains("wsj")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DD>")){ }
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DD>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
						}
						else if (files[pos].getAbsolutePath().contains("WSJ")){
							while ( (aux=input.readLine()) != null && !aux.trim().contains("<DATE>")){ }
					        is.setCharacterStream(new StringReader(aux));
					        doc = loader.parse(is);
							docno = (Node) xpath.compile("//TIMEX3").evaluate(doc,	XPathConstants.NODE);
							out.println("<p DOCCREATIONTIME=\""+docno.getAttributes().item(4).getTextContent()+"\"></p>");
						}
					} catch (SAXException e) {
						e.printStackTrace();
					} catch (XPathExpressionException e) {
						e.printStackTrace();
					} catch (ParserConfigurationException e) {
						e.printStackTrace();
					}
					while ( (aux=input.readLine()) != null && !aux.trim().equals("<TEXT>")) { }
					while ( (aux=input.readLine()) != null ) {
												
						if(aux.trim().contains("</TEXT>")) {
							str = aux2.split("<p>");
							//Substitute TIME3 tags with TIMEX2
							for (int i=0;i<str.length;i++){
								if(str[i].contains("<p>"))
									out.println(str[i].trim().replaceAll("<TIMEX3 [^v]* value=(^>)*", "<TIMEX2 val=$1")+"</p>");
								else if(str[i].contains("</p>"))
									out.println("<p>"+str[i].trim().replaceAll("<TIMEX3 [^v]* value=(^>)*", "<TIMEX2 val=$1"));
								else if(str[i].contains("TIMEX3"))
									out.println("<p>"+str[i].trim().replaceAll("<TIMEX3 [^v]* value=(^>)*", "<TIMEX2 val=$1")+"</p>");
								else if(str[i].contains("<p>") && str[i].contains("</p>"))
									out.println(str[i].trim());
								else if (str[i].contains("</p>"))
									out.println("<p>"+str[i].trim());
								else if (str[i].contains("<p>"))
									out.println(str[i].trim()+"</p>");
							}
						aux2 = " ";
						break;
						}
						if(aux.trim().contains("<turn")) {continue;}
						if(aux.trim().contains("</turn>")) {continue;}
						//Substitute tags that are not present in the scheme proposed
						aux = aux.replaceAll("<TIMEX3","<TIMEX2");
						aux = aux.replaceAll("</TIMEX3>","</TIMEX2>");
						aux = aux.replaceAll(" tid=\"[^\"]*\"","");
						aux = aux.replaceAll(" type=\"SET\""," set=\"YES\"");
						aux = aux.replaceAll(" temporalFunction=\"[^\"]*\"","");
						aux = aux.replaceAll(" endPoint=\"[^\"]*\"","");
						aux = aux.replaceAll(" functionInDocument=\"[^\"]*\"","");
						aux = aux.replaceAll(" anchorTimeID=\"[^\"]*\"","");
						aux = aux.replaceAll("beginPoint=\"[^\"]*\" ","");
						aux = aux.replaceAll(" mod=\"[^\"]*\"","");
						aux = aux.replaceAll("value=\"([^\"]*)\" ","val=\"$1\"");
						if (aux.contains("type=\"DURATION\"") || aux.contains("type=\"TIME\"") || aux.contains("type=\"DATE\""))
							aux = aux.replaceAll(" type=\"([^\"]*)\"","");
						aux = aux.replaceAll("<TIMEX [^>]*>","");aux = aux.replaceAll("</TIMEX>","");
						aux = aux.replaceAll("<ENAMEX[^>]*>","");aux = aux.replaceAll("</ENAMEX>","");
						aux = aux.replaceAll("<time[^>]*>","");aux = aux.replaceAll("</time>","");
						aux = aux.replaceAll("<section[^>]*>","");aux = aux.replaceAll("</section>","");
						aux = aux.replaceAll("<COMMENT[^>]*>","");aux = aux.replaceAll("</COMMENT>","");
						aux = aux.replaceAll("<EVENT[^>]*>","");aux = aux.replaceAll("</EVENT>","");
						aux = aux.replaceAll("<NUMEX[^>]*>","");aux = aux.replaceAll("</NUMEX>","");
						aux = aux.replaceAll("<SIGNAL[^>]*>","");aux = aux.replaceAll("</SIGNAL>","");
						aux = aux.replaceAll("<CARDINAL[^>]*>","");aux = aux.replaceAll("</CARDINAL>","");
						aux = aux.replaceAll("<HEAD>","");aux = aux.replaceAll("</HEAD>","");
						aux = aux.replaceAll("<NG[^>]*>","");aux = aux.replaceAll("</NG>","");
						aux = aux.replaceAll("<VG>","");aux = aux.replaceAll("</VG>","");
						aux = aux.replaceAll("<PG>","");aux = aux.replaceAll("</PG>","");
						aux = aux.replaceAll("<VG-INF>","");aux = aux.replaceAll("</VG-INF>","");
						aux = aux.replaceAll("<VG-VBG>","");aux = aux.replaceAll("</VG-VBG>","");
						aux = aux.replaceAll("<RG>","");aux = aux.replaceAll("</RG>","");
						aux = aux.replaceAll("<VG-VBN>","");aux = aux.replaceAll("</VG-VBN>","");
						aux = aux.replaceAll("<JG>","");aux = aux.replaceAll("</JG>","");
						aux = aux.replaceAll("<IN-MW>","");aux = aux.replaceAll("</IN-MW>","");
						aux = aux.replace("<TIMEX3", "<TIMEX2");
						aux = aux.replace("<s>", "<p>");
						aux = aux.replace("</s>", "</p>");
						aux = aux.replace("</p>.", ".</p>");
						
						aux2 = aux2.trim()+" "+aux.trim();
							
				}
					aux2=" ";
					out.println("</doc>");
				}
		}
		else if (path.contains("WikiWars")){
			System.out.println("WikiWars_Corpus");
			for (int pos = 0 ; pos < files.length ; pos++ ) {
				PrintWriter out = ( pos < split ) ? train : test;
				BufferedReader input = new BufferedReader(new FileReader(files[pos]));
				String aux = null;
				out.println("<doc>");
				while ( (aux=input.readLine()) != null) {
					if(aux.trim().contains("<DATETIME>")){
						aux = aux.replaceAll(" <TIMEX2[^>]*>([^<]*)</TIMEX2> ", "$1");
						out.println("<p DOCCREATIONTIME=\"" + aux.replace("<DATETIME>", "").replace("</DATETIME>", "") + "\"></p>");
						break;
					}
				}
					
				while ( (aux=input.readLine()) != null && !aux.trim().contains("<TEXT>")) { }
				while ( (aux=input.readLine()) != null){
					if(aux.trim().equals("</TEXT>")) break;					 
						
					aux = aux.replaceFirst("(<TIMEX2[^>]*>[^<]*)<TIMEX2[^>]*>([^<]*)</TIMEX2>([^<]*)","$1$2$3");
					
					out.println("<p>" + aux + "</p>");
				}
				out.println("</doc>");
			}
		}

		test.println("</corpus>");
		train.println("</corpus>");
		test.close();
		train.close();
	}

	public static void main(String args[]) throws Exception {
		if (args.length > 0)
			path = args[0];
		
	  //Prepara o corpus para estar de acordo com o que o sistema esta a espera
	  prepareCorpus(path, 80);
		  
	  PrintStream evaluationCRF = new PrintStream(new FileOutputStream(new File(path+"/../Wikiwars-evaluation-results-crf.txt"))); 
	  PrintStream annotationCRF = new PrintStream(new FileOutputStream(new File(path+"/../WikiWars_CRF-Recognition-annotation-results-crf.txt")));
	  CandidateCreation.init();
	  CandidateCreation.isXMLflag = true;
	  CandidateCreation.candidatesMillisecondsSameDoc.clear();
	  CandidateCreation.candidatesIntervalsSameDoc.clear();
	  CandidateCreation.candidatesMillisecondsSameSentence.clear();
	  CandidateCreation.candidatesIntervalsSameSentence.clear();
	  CandidateCreation.numberCandidatesTimex = 0;
	  //treina o modelo CRF
	  trainResolver(new File(path+"/../timex-train.xml"),new File(path+"/../timex.model.crf"));
	  String main[] = {path+"/../timex-train.xml"};
	  System.out.println("A entrar na desambiguacao");
	  //Treina modelo de regressao
	  TIMEXRegressionDisambiguation.main(main);

	  //Testa o sistema
	  testResolver(new File(path+"/../timex.model.crf"), "/Users/vitorloureiro/Desktop/Teste3/models/RegressionModel.svm", new File(path+"/../timex-test.xml"), evaluationCRF, annotationCRF);
	  evaluationCRF.close(); 
	  annotationCRF.close();
		 
	}

}