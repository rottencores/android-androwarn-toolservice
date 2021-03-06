/* This software was developed by employees of the National Institute of
 * Standards and Technology (NIST), an agency of the Federal Government.
 * Pursuant to title 15 United States Code Section 105, works of NIST
 * employees are not subject to copyright protection in the United States
 * and are considered to be in the public domain.  As a result, a formal
 * license is not needed to use the software.
 * 
 * This software is provided by NIST as a service and is expressly
 * provided "AS IS".  NIST MAKES NO WARRANTY OF ANY KIND, EXPRESS, IMPLIED
 * OR STATUTORY, INCLUDING, WITHOUT LIMITATION, THE IMPLIED WARRANTY OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, NON-INFRINGEMENT
 * AND DATA ACCURACY.  NIST does not warrant or make any representations
 * regarding the use of the software or the results thereof including, but
 * not limited to, the correctness, accuracy, reliability or usefulness of
 * the software.
 * 
 * Permission to use this software is contingent upon your acceptance
 * of the terms of this agreement.
 */
package gov.nist.appvet.tool.androwarn;

import gov.nist.appvet.tool.androwarn.util.FileUtil;
import gov.nist.appvet.tool.androwarn.util.HttpUtil;
import gov.nist.appvet.tool.androwarn.util.Logger;
import gov.nist.appvet.tool.androwarn.util.Protocol;
import gov.nist.appvet.tool.androwarn.util.ReportFormat;
import gov.nist.appvet.tool.androwarn.util.ReportUtil;
import gov.nist.appvet.tool.androwarn.util.ToolStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

/**
 * This class implements a synchronous tool service.
 */
public class Service extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final String reportName = "report";
	private static final Logger log = Properties.log;
	private static String appDirPath = null;
	private String appFilePath = null;
	private String reportFilePath = null;
	private String fileName = null;
	private String appId = null;
	private String command = null;
	private StringBuffer reportBuffer = null;
	private static final String LOW_DESCRIPTION = "Summary: \tAndrowarn found no vulnerabilities.\n\n";
	private static final String MODERATE_DESCRIPTION = "Summary: \tAndrowarn found moderate vulnerabilities.\n\n";
	private static final String HIGH_DESCRIPTION = "Summary: \tAndrowarn found high/severe vulnerabilities.\n\n";
	private static final String ERROR_DESCRIPTION = "Summary: \tAndrowarn encountered an error processing the app.\n\n";

	/** CHANGE (START): Add expected HTTP request parameters **/
	/** CHANGE (END): Add expected HTTP request parameters **/
	public Service() {
		super();
	}

	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		FileItemFactory factory = new DiskFileItemFactory();
		ServletFileUpload upload = new ServletFileUpload(factory);
		List items = null;
		FileItem fileItem = null;

		try {
			items = upload.parseRequest(request);
		} catch (FileUploadException e) {
			e.printStackTrace();
		}

		// Get received items
		Iterator iter = items.iterator();
		FileItem item = null;

		while (iter.hasNext()) {
			item = (FileItem) iter.next();
			if (item.isFormField()) {
				// Get HTML form parameters
				String incomingParameter = item.getFieldName();
				String incomingValue = item.getString();
				if (incomingParameter.equals("appid")) {
					appId = incomingValue;
				}
				/** CHANGE (START): Get other tools-specific form parameters **/
				/** CHANGE (END): Get other tools-specific form parameters **/
			} else {
				// item should now hold the received file
				if (item != null) {
					fileItem = item;
					log.debug("Received file: " + fileItem.getName());
				}
			}
		}

		if (appId == null) {
			// All tool services require an AppVet app ID
			HttpUtil.sendHttp400(response, "No app ID specified");
			return;
		}

		if (fileItem != null) {
			// Get app file
			fileName = FileUtil.getFileName(fileItem.getName());
			if (!fileName.endsWith(".apk")) {
				HttpUtil.sendHttp400(response,
						"Invalid app file: " + fileItem.getName());
				return;
			}
			// Create app directory
			appDirPath = Properties.TEMP_DIR + "/" + appId;
			File appDir = new File(appDirPath);
			if (!appDir.exists()) {
				appDir.mkdir();
			}
			// Create report path
			reportFilePath = Properties.TEMP_DIR + "/" + appId + "/"
					+ reportName + "." + Properties.reportFormat.toLowerCase();

			appFilePath = Properties.TEMP_DIR + "/" + appId + "/" + fileName;
			log.debug("App file path: " + appFilePath);

			if (!FileUtil.saveFileUpload(fileItem, appFilePath)) {
				log.error("Could not save uploaded file");
				HttpUtil.sendHttp500(response, "Could not save uploaded file");
				return;
			} else {
				log.debug(appFilePath + " saved successfully");
			}
		} else {
			log.error("No app was received");
			HttpUtil.sendHttp400(response, "No app was received.");
			return;
		}

		// Use if reading command from ToolProperties.xml. Otherwise,
		// comment-out if using custom command (called by customExecute())
		command = getCommand();
		log.debug("Command to execute: " + command);
		reportBuffer = new StringBuffer();

		// If asynchronous, send acknowledgement back to AppVet now
		if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			log.debug("Sending HTTP202 back to AppVet");
			HttpUtil.sendHttp202(response, "Received app " + appId
					+ " for processing.");
		} else {
			log.warn("Did not send HTTP202 back to AppVet. Incorrect protocol");
		}

		/*
		 * CHANGE: Select either execute() to execute a native OS command or
		 * customExecute() to execute your own custom code. Make sure that the
		 * unused method call is commented-out.
		 */
		boolean succeeded = execute(command, reportBuffer);
		// boolean succeeded = customExecute(reportBuffer);

		if (!succeeded) {
			log.error("Error detected: " + reportBuffer.toString());
			String errorReport = ReportUtil.getHtmlReport(response, fileName,
					ToolStatus.ERROR, reportBuffer.toString(), LOW_DESCRIPTION,
					MODERATE_DESCRIPTION, HIGH_DESCRIPTION, ERROR_DESCRIPTION);
			// Send report to AppVet
			if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
				// Send back ASCII in HTTP Response
				ReportUtil.sendInHttpResponse(response, errorReport,
						ToolStatus.ERROR);
			} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
				// Send report file in new HTTP Request to AppVet
				if (FileUtil.saveReport(errorReport, reportFilePath)) {
					ReportUtil.sendInNewHttpRequest(appId, reportFilePath,
							ToolStatus.ERROR);
				}
			}
			return;
		} else {
			log.debug("Command executed successfully");
		}

		// Analyze report and generate tool status
		log.debug("Analyzing report for " + appFilePath);
		ToolStatus reportStatus = analyzeReport(reportBuffer
				.toString());
		log.debug("Result: " + reportStatus.name());
		String reportContent = null;

		// Get report
		if (Properties.reportFormat.equals(ReportFormat.HTML.name())) {
			reportContent = ReportUtil.getHtmlReport(response, fileName,
					reportStatus, reportBuffer.toString(), LOW_DESCRIPTION,
					MODERATE_DESCRIPTION, HIGH_DESCRIPTION, ERROR_DESCRIPTION);
		} else if (Properties.reportFormat.equals(ReportFormat.TXT.name())) {
			reportContent = getTxtReport();
		} else if (Properties.reportFormat.equals(ReportFormat.PDF.name())) {
			reportContent = getPdfReport();
		} else if (Properties.reportFormat.equals(ReportFormat.DOCX.name())) {
			reportContent = getDocxReport();
		} else if (Properties.reportFormat.equals(ReportFormat.JSON.name())) {
			reportContent = getJsonReport();
		}

		// If report is null or empty, stop processing
		if (reportContent == null || reportContent.isEmpty()) {
			log.error("Tool report is null or empty");
			return;
		}

		// Send report to AppVet
		if (Properties.protocol.equals(Protocol.SYNCHRONOUS.name())) {
			// Send back ASCII in HTTP Response
			ReportUtil
			.sendInHttpResponse(response, reportContent, reportStatus);
		} else if (Properties.protocol.equals(Protocol.ASYNCHRONOUS.name())) {
			// Send report file in new HTTP Request to AppVet
			if (FileUtil.saveReport(reportContent, reportFilePath)) {
				ReportUtil.sendInNewHttpRequest(appId, reportFilePath,
						reportStatus);
			}
		}

		// Clean up
		if (!Properties.keepApps) {
			if (FileUtil.deleteDirectory(new File(appDirPath))) {
				log.debug("Deleted " + appFilePath);
			} else {
				log.warn("Could not delete " + appFilePath);
			}
		} else {
			log.warn("keepApps is set to true. Keeping app");
		}

		reportBuffer = null;
		
		// Clean up
		System.gc();
		log.debug("End processing");
	}
	
	public static ToolStatus analyzeReport(String report) {
		if (report == null || report.isEmpty()) {
			log.error("Report is null or empty.");
			return ToolStatus.ERROR;
		}
		// Scan file for result strings defined in configuration file. Here,
		// we always scan in this order: ERRORs, HIGHs, MODERATEs, and LOWs.
		if (Properties.errorResults != null
				&& !Properties.errorResults.isEmpty()) {
			for (String s : Properties.errorResults) {
				if (report.indexOf(s) > -1) {
					log.debug("Found ERROR message: " + s);
					return ToolStatus.ERROR;
				}
			}
		}
		if (Properties.highResults != null && !Properties.highResults.isEmpty()) {
			for (String s : Properties.highResults) {
				if (report.indexOf(s) > -1) {
					log.debug("Found HIGH message: " + s);
					return ToolStatus.HIGH;
				}
			}
		}
		if (Properties.moderateResults != null
				&& !Properties.moderateResults.isEmpty()) {
			for (String s : Properties.moderateResults) {
				if (report.indexOf(s) > -1) {
					log.debug("Found MODERATE message: " + s);
					return ToolStatus.MODERATE;
				}
			}
		}
		if (Properties.lowResults != null && !Properties.lowResults.isEmpty()) {
			for (String s : Properties.lowResults) {
				if (report.indexOf(s) > -1) {
					log.debug("Found LOW message: " + s);
					return ToolStatus.LOW;
				}
			}
		}
		return Properties.defaultStatus;
	}

	public String getCommand() {
		// Get command from ToolProperties.xml file
		String cmd1 = Properties.command;
		String cmd2 = null;
		String cmd3 = null;
		if (cmd1.indexOf(Properties.APP_FILE_PATH) > -1) {
			// Add app file path
			cmd2 = cmd1.replace(Properties.APP_FILE_PATH, appFilePath);
		} else {
			cmd2 = cmd1;
		}
		if (cmd2.indexOf(Properties.ANDROWARN_HOME_SUB) > -1) {
			// Add output (e.g., report) file path
			cmd3 = cmd2.replace(Properties.ANDROWARN_HOME_SUB,
					Properties.ANDROWARN_HOME);
		} else {
			cmd3 = cmd2;
		}
		return cmd3;
	}

	private static boolean execute(String command, StringBuffer output) {
		List<String> commandArgs = Arrays.asList(command.split("\\s+"));
		ProcessBuilder pb = new ProcessBuilder(commandArgs);
		Process process = null;
		IOThreadHandler outputHandler = null;
		IOThreadHandler errorHandler = null;
		int exitValue = -1;
		try {
			if (command == null || command.isEmpty()) {
				log.error("Command is null or empty");
				return false;
			}
			log.debug("Executing " + command);
			process = pb.start();
			outputHandler = new IOThreadHandler(process.getInputStream());
			outputHandler.start();
			errorHandler = new IOThreadHandler(process.getErrorStream());
			errorHandler.start();
			if (process.waitFor(Properties.commandTimeout,
					TimeUnit.MILLISECONDS)) {
				// Process has waited and exited successfully within the timeout
				exitValue = process.exitValue();
				if (exitValue == 0) {
					log.debug("Command executed successfully");
					StringBuffer resultOut = outputHandler.getOutput();
					output.append(resultOut);
				} else {
					log.warn("Command execution failed");
					StringBuffer resultError = errorHandler.getOutput();
					output.append(resultError);
				}
				return true;
			} else {
				// Process exceeded timeout or was interrupted
				log.error("Andorwarn timed-out or was interrupted");
				StringBuffer resultOutput = outputHandler.getOutput();
				StringBuffer resultError = errorHandler.getOutput();
				if (resultOutput != null) {
					log.debug(resultOutput.toString());
					output.append(resultOutput);
				} else if (resultError != null) {
					log.error(resultError.toString());
					output.append(resultError);
				}
				return false;
			}
		} catch (IOException e) {
			log.error(e.getMessage());
			return false;
		} catch (InterruptedException e) {
			log.error(e.getMessage());
			return false;
		} finally {
			if (outputHandler.isAlive()) {
				try {
					outputHandler.inputStream.close();
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			if (errorHandler.isAlive()) {
				try {
					errorHandler.inputStream.close();
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			if (process.isAlive()) {
				process.destroy();
			}
		}

	}

	private static class IOThreadHandler extends Thread {
		private InputStream inputStream;
		private StringBuffer output = new StringBuffer();
		private static final String lineSeparator = System
				.getProperty("line.separator");

		IOThreadHandler(InputStream inputStream) {
			this.inputStream = inputStream;
		}

		public void run() {
			Scanner br = null;
			br = new Scanner(new InputStreamReader(inputStream));
			String line = null;
			while (br.hasNextLine()) {
				line = br.nextLine();
				output.append(line + lineSeparator);
			}
			br.close();
		}

		public StringBuffer getOutput() {
			return output;
		}
	}
	
	// TODO
	public String getTxtReport() {
		return null;
	}

	// TODO
	public String getDocxReport() {
		return null;
	}

	// TODO
	public String getPdfReport() {
		return null;
	}

	// TODO
	public String getJsonReport() {
		return null;
	}
}
