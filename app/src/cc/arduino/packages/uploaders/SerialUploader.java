/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  BasicUploader - generic command line uploader implementation
  Part of the Arduino project - http://www.arduino.cc/

  Copyright (c) 2004-05
  Hernando Barragan
  Copyright (c) 2012
  Cristian Maglie <c.maglie@bug.st>

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package cc.arduino.packages.uploaders;

import static processing.app.I18n._;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import processing.app.Base;
import processing.app.I18n;
import processing.app.Preferences;
import processing.app.Serial;
import processing.app.SerialException;
import processing.app.debug.RunnerException;
import processing.app.debug.TargetPlatform;
import processing.app.helpers.PreferencesMap;
import processing.app.helpers.StringReplacer;
import cc.arduino.packages.Uploader;

public class SerialUploader extends Uploader {

  static final int uploadNormal = 0;
  static final int uploadUsingProgrammer = 1;
  static final int uploadToMemory = 2;

  public boolean uploadUsingPreferences(File sourcePath, String buildPath, String className,
                                        int uploadOptions, List<String> warningsAccumulator)
      throws Exception {
    // FIXME: Preferences should be reorganized
    TargetPlatform targetPlatform = Base.getTargetPlatform();
    PreferencesMap prefs = Preferences.getMap();
    prefs.putAll(Base.getBoardPreferences());
    String tool = prefs.getOrExcept("upload.tool");
    if (tool.contains(":")) {
      String[] split = tool.split(":", 2);
      targetPlatform = Base.getCurrentTargetPlatformFromPackage(split[0]);
      tool = split[1];
    }
    prefs.putAll(targetPlatform.getTool(tool));

    // if no protocol is specified for this board, assume it lacks a 
    // bootloader and upload using the selected programmer.
    if ((uploadOptions == uploadUsingProgrammer) || prefs.get("upload.protocol") == null) {
      return uploadUsingProgrammer(buildPath, className);
    }

    // need to do a little dance for Leonardo and derivatives:
    // open then close the port at the magic baudrate (usually 1200 bps) first
    // to signal to the sketch that it should reset into bootloader. after doing
    // this wait a moment for the bootloader to enumerate. On Windows, also must
    // deal with the fact that the COM port number changes from bootloader to
    // sketch.
    String t = prefs.get("upload.use_1200bps_touch");
    boolean doTouch = t != null && t.equals("true");

    t = prefs.get("upload.wait_for_upload_port");
    boolean waitForUploadPort = (t != null) && t.equals("true");

    if (doTouch) {
      String uploadPort = prefs.getOrExcept("serial.port");
      try {
        // Toggle 1200 bps on selected serial port to force board reset.
        List<String> before = Serial.list();
        if (before.contains(uploadPort)) {
          if (verbose)
            System.out.println(_("Forcing reset using 1200bps open/close on port ") + uploadPort);
          Serial.touchPort(uploadPort, 1200);
        }
        Thread.sleep(400);
        if (waitForUploadPort) {
          // Scanning for available ports seems to open the port or
          // otherwise assert DTR, which would cancel the WDT reset if
          // it happened within 250 ms. So we wait until the reset should
          // have already occured before we start scanning.
          uploadPort = waitForUploadPort(uploadPort, before);
        }
      } catch (SerialException e) {
        throw new RunnerException(e);
      } catch (InterruptedException e) {
        throw new RunnerException(e.getMessage());
      }
      prefs.put("serial.port", uploadPort);
      if (uploadPort.startsWith("/dev/"))
        prefs.put("serial.port.file", uploadPort.substring(5));
      else
        prefs.put("serial.port.file", uploadPort);
    }

    prefs.put("build.path", buildPath);
    prefs.put("build.project_name", className);
    if (verbose)
      prefs.put("upload.verbose", prefs.getOrExcept("upload.params.verbose"));
    else
      prefs.put("upload.verbose", prefs.getOrExcept("upload.params.quiet"));

    prefs.put("upload.smallfs","");
    File smallfsfile = new File(buildPath,"smallfs.dat");
    if (smallfsfile.exists()) {
        if (uploadOptions==uploadToMemory) {
            /* Warn user only */
            System.err.println(_("SmallFS filesystem found, *NOT* programming FLASH because you're doing a memory upload"));
        } else {
            t = prefs.get("upload.params.smallfs");
            if (t!=null) {
                System.out.println("Upload param is "+t);
                prefs.put("upload.smallfs", t);
            } else {
                throw new RunnerException("SmallFS found, but no uploader is available");
            }
            System.out.println(_("SmallFS filesystem found, appending extra data file to FLASH"));
        }
    }

    prefs.put("upload.memory","");
    if (uploadOptions==uploadToMemory) {
        t = prefs.get("upload.params.memory");
        if (t==null) {
            System.err.println(_("Memory upload requested, but I don't know how to handle it for this platform"));
            throw new RunnerException(_("Memory upload not possible"));
        }
        prefs.put("upload.memory",t);
    }

    boolean uploadResult;
    try {
//      if (prefs.get("upload.disable_flushing") == null
//          || prefs.get("upload.disable_flushing").toLowerCase().equals("false")) {
//        flushSerialBuffer();
//      }

      //If we need to load the Arduino DUO ISP bit file first.
      String protocol = prefs.get("upload.protocol");
      if (protocol.contains("duoisp")){
        //Load the ISP bit file
        String pattern = prefs.get("duoisp.pattern");
        String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false;
        
        //Program the hex file with avrdude
        pattern = prefs.get("duoisp.pattern2");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false; 
        
        //Restart FPGA
        pattern = prefs.get("duoisp.pattern3");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false;
        pattern = prefs.get("duoisp.pattern4");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        return executeUploadCommand(cmd);      
      } 
      
      String pattern = prefs.getOrExcept("upload.pattern");
      String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      uploadResult = executeUploadCommand(cmd);
    } catch (RunnerException e) {
      throw e;
    } catch (Exception e) {
      throw new RunnerException(e);
    }

    try {
      if (uploadResult && doTouch) {
        String uploadPort = Preferences.get("serial.port");
        if (waitForUploadPort) {
          // For Due/Leonardo wait until the bootloader serial port disconnects and the
          // sketch serial port reconnects (or timeout after a few seconds if the
          // sketch port never comes back). Doing this saves users from accidentally
          // opening Serial Monitor on the soon-to-be-orphaned bootloader port.
          Thread.sleep(1000);
          long started = System.currentTimeMillis();
          while (System.currentTimeMillis() - started < 2000) {
            List<String> portList = Serial.list();
            if (portList.contains(uploadPort))
              break;
            Thread.sleep(250);
          }
        }
      }
    } catch (InterruptedException ex) {
      // noop
    }
    return uploadResult;
  }

  private String waitForUploadPort(String uploadPort, List<String> before) throws InterruptedException, RunnerException {
    // Wait for a port to appear on the list
    int elapsed = 0;
    while (elapsed < 10000) {
      List<String> now = Serial.list();
      List<String> diff = new ArrayList<String>(now);
      diff.removeAll(before);
      if (verbose) {
        System.out.print("PORTS {");
        for (String p : before)
          System.out.print(p + ", ");
        System.out.print("} / {");
        for (String p : now)
          System.out.print(p + ", ");
        System.out.print("} => {");
        for (String p : diff)
          System.out.print(p + ", ");
        System.out.println("}");
      }
      if (diff.size() > 0) {
        String newPort = diff.get(0);
        if (verbose)
          System.out.println("Found upload port: " + newPort);
        return newPort;
      }

      // Keep track of port that disappears
      before = now;
      Thread.sleep(250);
      elapsed += 250;

      // On Windows, it can take a long time for the port to disappear and
      // come back, so use a longer time out before assuming that the
      // selected
      // port is the bootloader (not the sketch).
      if (((!Base.isWindows() && elapsed >= 500) || elapsed >= 5000) && now.contains(uploadPort)) {
        if (verbose)
          System.out.println("Uploading using selected port: " + uploadPort);
        return uploadPort;
      }
    }

    // Something happened while detecting port
    throw new RunnerException(_("Couldn't find a Board on the selected port. Check that you have the correct port selected.  If it is correct, try pressing the board's reset button after initiating the upload."));
  }

  public boolean uploadUsingProgrammer(String buildPath, String className) throws Exception {

    TargetPlatform targetPlatform = Base.getTargetPlatform();
    String programmer = Preferences.get("programmer");
    if (programmer.contains(":")) {
      String[] split = programmer.split(":", 2);
      targetPlatform = Base.getCurrentTargetPlatformFromPackage(split[0]);
      programmer = split[1];
    }

    PreferencesMap prefs = Preferences.getMap();
    prefs.putAll(Base.getBoardPreferences());
    PreferencesMap programmerPrefs = targetPlatform.getProgrammer(programmer);
    if (programmerPrefs == null)
      throw new RunnerException(
          _("Please select a programmer from Tools->Programmer menu"));
    prefs.putAll(programmerPrefs);
    prefs.putAll(targetPlatform.getTool(prefs.getOrExcept("program.tool")));

    prefs.put("build.path", buildPath);
    prefs.put("build.project_name", className);

    if (verbose)
      prefs.put("program.verbose", prefs.getOrExcept("program.params.verbose"));
    else
      prefs.put("program.verbose", prefs.getOrExcept("program.params.quiet"));

    try {
      // if (prefs.get("program.disable_flushing") == null
      // || prefs.get("program.disable_flushing").toLowerCase().equals("false"))
      // {
      // flushSerialBuffer();
      // }

      //If we need to load the Arduino DUO ISP bit file first.
      if (programmer.contains("duoisp")){
        //Load the ISP bit file
        String pattern = prefs.get("duoisp.pattern");
        String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false;
        
        //Program the hex file with avrdude
        pattern = prefs.get("duoisp.pattern2");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false; 
        
        //Restart FPGA
        pattern = prefs.get("duoisp.pattern3");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false;
        pattern = prefs.get("duoisp.pattern4");
        cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        return executeUploadCommand(cmd);         
      }      
      
      String pattern = prefs.getOrExcept("program.pattern");
      String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      return executeUploadCommand(cmd);
    } catch (RunnerException e) {
      throw e;
    } catch (Exception e) {
      throw new RunnerException(e);
    }
  }

  public boolean burnBootloader() throws Exception {
    TargetPlatform targetPlatform = Base.getTargetPlatform();

    // Find preferences for the selected programmer
    PreferencesMap programmerPrefs;
    String programmer = Preferences.get("programmer");
    if (programmer.contains(":")) {
      String[] split = programmer.split(":", 2);
      TargetPlatform platform = Base.getCurrentTargetPlatformFromPackage(split[0]);
      programmer = split[1];
      programmerPrefs = platform.getProgrammer(programmer);
    } else {
      programmerPrefs = targetPlatform.getProgrammer(programmer);
    }
    if (programmerPrefs == null)
      throw new RunnerException(
          _("Please select a programmer from Tools->Programmer menu"));

    // Build configuration for the current programmer
    PreferencesMap prefs = Preferences.getMap();
    prefs.putAll(Base.getBoardPreferences());
    prefs.putAll(programmerPrefs);

    // Create configuration for bootloader tool
    PreferencesMap toolPrefs = new PreferencesMap();
    String tool = prefs.getOrExcept("bootloader.tool");
    if (tool.contains(":")) {
      String[] split = tool.split(":", 2);
      TargetPlatform platform = Base.getCurrentTargetPlatformFromPackage(split[0]);
      tool = split[1];
      toolPrefs.putAll(platform.getTool(tool));
      if (toolPrefs.size() == 0)
        throw new RunnerException(I18n.format(_("Could not find tool {0} from package {1}"), tool, split[0]));
    }
    toolPrefs.putAll(targetPlatform.getTool(tool));
    if (toolPrefs.size() == 0)
      throw new RunnerException(I18n.format(_("Could not find tool {0}"), tool));

    // Merge tool with global configuration
    prefs.putAll(toolPrefs);
    if (verbose) {
      prefs.put("erase.verbose", prefs.getOrExcept("erase.params.verbose"));
      prefs.put("bootloader.verbose", prefs.getOrExcept("bootloader.params.verbose"));
    } else {
      prefs.put("erase.verbose", prefs.getOrExcept("erase.params.quiet"));
      prefs.put("bootloader.verbose", prefs.getOrExcept("bootloader.params.quiet"));
    }

    try {
      
      //If we need to load the Arduino DUO ISP bit file first.
      if (programmer.contains("duoisp")){
        String pattern = prefs.get("duoisp.pattern");
        String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
        if (!executeUploadCommand(cmd))
          return false;
      }        
      String pattern = prefs.getOrExcept("erase.pattern");
      String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      if (!executeUploadCommand(cmd))
        return false;

      pattern = prefs.getOrExcept("bootloader.pattern");
	  //System.out.println(_("Burning bit file to Papilio Board."));
      cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      if (!executeUploadCommand(cmd))
        return false;
      
      //Restart FPGA
      pattern = prefs.get("duoisp.pattern3");
      cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      if (!executeUploadCommand(cmd))
        return false;
      pattern = prefs.get("duoisp.pattern4");
      cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      return executeUploadCommand(cmd);       
    } catch (RunnerException e) {
      throw e;
    } catch (Exception e) {
      throw new RunnerException(e);
    }
  }
  
  public boolean burnBitfile(String path) throws RunnerException {
//    String programmer = Preferences.get("programmer");
    TargetPlatform targetPlatform = Base.getTargetPlatform();
//    if (programmer.contains(":")) {
//      String[] split = programmer.split(":", 2);
//      targetPlatform = Base.getCurrentTargetPlatformFromPackage(split[0]);
//      programmer = split[1];
//    }
    
    PreferencesMap prefs = Preferences.getMap();
    prefs.putAll(Base.getBoardPreferences());
//    prefs.putAll(targetPlatform.getProgrammer(programmer));
    prefs.putAll(targetPlatform.getTool(prefs.get("bitloader.tool")));
    if (verbose) {
      prefs.put("erase.verbose", prefs.get("erase.params.verbose"));
      prefs.put("bitloader.verbose", prefs.get("bitloader.params.verbose"));
    } else {
      prefs.put("erase.verbose", prefs.get("erase.params.quiet"));
      prefs.put("bitloader.verbose", prefs.get("bitloader.params.quiet"));
    }
    
    try {
      // if (prefs.get("program.disable_flushing") == null
      // || prefs.get("program.disable_flushing").toLowerCase().equals("false"))
      // {
      // flushSerialBuffer();
      // }
      String temp =  prefs.get("bitloader.pattern2");
      String pattern = temp + " \"" + path + "\"";
	  //Base.showMessage("Title", pattern);
	  //System.out.println(_("Burning bit file to Papilio Board."));
      String[] cmd = StringReplacer.formatAndSplit(pattern, prefs, true);
      return executeUploadCommand(cmd);
    } catch (Exception e) {
      throw new RunnerException(e);
    }
  } 
  
}
