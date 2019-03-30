package com.maxieds.MifareClassicToolLibrary;

//import android.R;
//import com.example.package.R;
import android.util.Log;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.content.Context;
import android.os.Parcel;
import android.os.IBinder;
import android.os.Bundle;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.maxieds.MifareClassicToolLibrary.MifareClassicLibraryException.MFCLibraryExceptionType.*;

public class MifareClassicTag {

     private static final String TAG = MifareClassicTag.class.getSimpleName();

     public static final int MFCKEY_BYTE_SIZE = 6;

     public static class MFCSector {

         public int sectorAddress;
         public int sectorSize;
         public int sectorBlockCount;
         public int sectorFirstBlock;
         public int sectorBytesPerBlock;
         public byte[] sectorAccessBits;
         public byte[] keyA, keyB;
         public byte[][] sectorBlockData;

         public MFCSector() {
             sectorAddress = sectorSize = sectorBlockCount = sectorFirstBlock = sectorBytesPerBlock = 0;
             sectorAccessBits = keyA = keyB = null;
             sectorBlockData = null;
         }

         public void FromTag(Tag nfcTag, int saddr) throws MifareClassicLibraryException {
             MifareClassic mfcTag = MifareClassic.get(nfcTag);
             if(mfcTag == null) {
                 throw new MifareClassicLibraryException(UnsupportedTagException);
             }
             sectorAddress = saddr;
             sectorFirstBlock = mfcTag.sectorToBlock(sectorAddress);
             sectorBlockCount = mfcTag.getBlockCountInSector(sectorAddress);
             sectorBytesPerBlock = MifareClassic.BLOCK_SIZE;
             sectorSize = sectorBlockCount * sectorBytesPerBlock;
         }

         public int ReadSector(Tag nfcTag, String[] trialKeysList) throws MifareClassicLibraryException {
              if(nfcTag == null) {
                   throw new MifareClassicLibraryException(NFCErrorException);
              }
              else if(trialKeysList == null) {
                   throw new MifareClassicLibraryException(InvalidKeysException);
              }
              MifareClassic mfcTag = MifareClassic.get(nfcTag);
              if(mfcTag == null) {
                   throw new MifareClassicLibraryException(NFCErrorException);
              }
              try {
                   mfcTag.connect();
                   if(!mfcTag.isConnected()) {
                        throw new MifareClassicLibraryException(NFCErrorException);
                   }
                   //mfcTag.setTimeout(1000);
              } catch(IOException ioe) {
                   ioe.printStackTrace();
                   throw new MifareClassicLibraryException(NFCErrorException, ioe.getMessage());
              }
              // try to "crack" the keys with a preset list of values before we proceed:
              boolean authedKeyA = false, authedKeyB = false;
              int curAuthAttempt = 0;
              while(curAuthAttempt <= MifareClassicToolLibrary.RETRIES_TO_AUTH_KEYAB) {
                   for (int kidx = 0; kidx < trialKeysList.length; kidx++) {
                        try {
                             byte[] activeKey = MCTUtils.HexStringToBytes(trialKeysList[kidx]);
                             if (!authedKeyA && mfcTag.authenticateSectorWithKeyA(sectorAddress, activeKey)) {
                                  authedKeyA = true;
                                  keyA = new byte[MFCKEY_BYTE_SIZE];
                                  System.arraycopy(activeKey, 0, keyA, 0, MFCKEY_BYTE_SIZE);
                             }
                             if (!authedKeyB && mfcTag.authenticateSectorWithKeyB(sectorAddress, activeKey)) {
                                  authedKeyB = true;
                                  keyB = new byte[MFCKEY_BYTE_SIZE];
                                  System.arraycopy(activeKey, 0, keyB, 0, MFCKEY_BYTE_SIZE);
                             }
                             if (authedKeyA && authedKeyB) {
                                  break;
                             }
                        } catch (IOException ioe) {
                             ioe.printStackTrace();
                             try {
                                  mfcTag.close();
                             } catch (IOException ioeClose) {
                                  ioeClose.printStackTrace();
                             }
                             throw new MifareClassicLibraryException(NFCErrorException, ioe.getMessage());
                        }
                   }
                   if(authedKeyA && authedKeyB) {
                        break;
                   }
                   else if(!MifareClassicToolLibrary.RETRY_AUTH_IFNOT_BOTH && (authedKeyA || authedKeyB)) {
                        break;
                   }
                   curAuthAttempt++;
              }
              try {
                   if (authedKeyA) {
                        mfcTag.authenticateSectorWithKeyA(sectorAddress, keyA);
                   }
                   else if (authedKeyB) {
                        mfcTag.authenticateSectorWithKeyB(sectorAddress, keyB);
                   }
              } catch(IOException ioe) {
                   ioe.printStackTrace();
                   try {
                        mfcTag.close();
                   } catch(IOException ioeClose) {
                        ioeClose.printStackTrace();
                   }
                   throw new MifareClassicLibraryException(NFCErrorException, ioe.getMessage());
              }
              int totalBytesRead = 0;
              sectorBlockData = new byte[sectorBlockCount][];
              for(int blk = 0; blk < sectorBlockCount; blk++) {
                   sectorBlockData[blk] = new byte[sectorBytesPerBlock];
                   try {
                        byte[] blockDataBytes = mfcTag.readBlock(sectorFirstBlock + blk);
                        if(blockDataBytes != null) {
                             System.arraycopy(blockDataBytes, 0, sectorBlockData[blk], 0, blockDataBytes.length);
                             totalBytesRead += blockDataBytes.length;
                             if(blk == sectorBlockCount - 1 && blockDataBytes.length >= 10) { // in the trailer key and access bit block:
                                  sectorAccessBits = new byte[4];
                                  System.arraycopy(blockDataBytes, 6, sectorAccessBits, 0, 4);
                             }
                        }
                   } catch(Exception ioe) {
                        try {
                             mfcTag.close();
                        } catch(IOException ioeClose) {
                             ioeClose.printStackTrace();
                        }
                        throw new MifareClassicLibraryException(PartialReadException, ioe.getMessage());
                   }
              }
              try {
                   mfcTag.close();
              } catch(IOException ioe) {
                   ioe.printStackTrace();
              }
              return totalBytesRead;
         }

         public String GetAccessBytesDescription() {
             return null;
         }

     }

     /**
      * Patch a possibly broken Tag object of HTC One (m7/m8) or Sony
      * Xperia Z3 devices (with Android 5.x.)
      *
      * HTC One: "It seems, the reason of this bug is TechExtras of NfcA is null.
      * However, TechList contains MifareClassic." -- bildin.
      * This method will fix this. For more information please refer to
      * https://github.com/ikarus23/MifareClassicTool/issues/52
      * This patch was provided by bildin (https://github.com/bildin).
      *
      * Sony Xperia Z3 (+ emmulated MIFARE Classic tag): The buggy tag has
      * two NfcA in the TechList with different SAK values and a MifareClassic
      * (with the Extra of the second NfcA). Both, the second NfcA and the
      * MifareClassic technique, have a SAK of 0x20. According to NXP's
      * guidelines on identifying MIFARE tags (Page 11), this a MIFARE Plus or
      * MIFARE DESFire tag. This method creates a new Extra with the SAK
      * values of both NfcA occurrences ORed (as mentioned in NXP's
      * MIFARE type identification procedure guide) and replace the Extra of
      * the first NfcA with the new one. For more information please refer to
      * https://github.com/ikarus23/MifareClassicTool/issues/64
      * This patch was provided by bildin (https://github.com/bildin).
      *
      * @param tag The possibly broken tag.
      * @return The fixed tag.
      */
     public static Tag patchTag(Tag tag) {
          if (tag == null) {
               return null;
          }
          String[] techList = tag.getTechList();
          Parcel oldParcel = Parcel.obtain();
          tag.writeToParcel(oldParcel, 0);
          oldParcel.setDataPosition(0);
          int len = oldParcel.readInt();
          byte[] id = new byte[0];
          if (len >= 0) {
               id = new byte[len];
               oldParcel.readByteArray(id);
          }
          int[] oldTechList = new int[oldParcel.readInt()];
          oldParcel.readIntArray(oldTechList);
          Bundle[] oldTechExtras = oldParcel.createTypedArray(Bundle.CREATOR);
          int serviceHandle = oldParcel.readInt();
          int isMock = oldParcel.readInt();
          IBinder tagService;
          if (isMock == 0) {
               tagService = oldParcel.readStrongBinder();
          } else {
               tagService = null;
          }
          oldParcel.recycle();
          int nfcaIdx = -1;
          int mcIdx = -1;
          short sak = 0;
          boolean isFirstSak = true;
          for (int i = 0; i < techList.length; i++) {
               if (techList[i].equals(NfcA.class.getName())) {
                    if (nfcaIdx == -1) {
                         nfcaIdx = i;
                    }
                    if (oldTechExtras[i] != null && oldTechExtras[i].containsKey("sak")) {
                         sak = (short) (sak | oldTechExtras[i].getShort("sak"));
                         isFirstSak = nfcaIdx == i;
                    }
               } else if (techList[i].equals(MifareClassic.class.getName())) {
                    mcIdx = i;
               }
          }
          boolean modified = false;
          // Patch the double NfcA issue (with different SAK) for
          // Sony Z3 devices.
          if (!isFirstSak) {
               oldTechExtras[nfcaIdx].putShort("sak", sak);
               modified = true;
          }
          // Patch the wrong index issue for HTC One devices.
          if (nfcaIdx != -1 && mcIdx != -1 && oldTechExtras[mcIdx] == null) {
               oldTechExtras[mcIdx] = oldTechExtras[nfcaIdx];
               modified = true;
          }
          if (!modified) {
               // Old tag was not modivied. Return the old one.
               return tag;
          }
          // Old tag was modified. Create a new tag with the new data.
          Parcel newParcel = Parcel.obtain();
          newParcel.writeInt(id.length);
          newParcel.writeByteArray(id);
          newParcel.writeInt(oldTechList.length);
          newParcel.writeIntArray(oldTechList);
          newParcel.writeTypedArray(oldTechExtras, 0);
          newParcel.writeInt(serviceHandle);
          newParcel.writeInt(isMock);
          if (isMock == 0) {
               newParcel.writeStrongBinder(tagService);
          }
          newParcel.setDataPosition(0);
          Tag newTag = Tag.CREATOR.createFromParcel(newParcel);
          newParcel.recycle();
          return newTag;
     }

     private String mfcTagType;
     private int tagSize, tagSectorCount, tagBlockCount, tagBytesPerBlock;
     private byte[] mfcDumpImageData;
     private List<MFCSector> failedSectors, tagSectors;
     private String rfTechCaps, tagManufacturer;
     private String tagUID, tagATQA, tagSAK, tagATS;

     private boolean ResetParameters() {
         mfcTagType = "";
         tagSize = tagSectorCount = tagBlockCount = tagBytesPerBlock = 0;
         mfcDumpImageData = null;
         failedSectors = new ArrayList<MFCSector>();
         tagSectors = new ArrayList<MFCSector>();
         rfTechCaps = tagManufacturer = "";
         tagUID = tagATQA = tagSAK = tagATS = "";
         return true;
     }

     private MifareClassicTag() {
         ResetParameters();
     }

     public static final int MFC_FULL_SUPPORT = 0;
     public static final int NO_MFC_DEVICE_SUPPORT = -1;
     public static final int NO_MFC_TAG_SUPPORT = -2;
     public static final int MFC_TAG_ERROR = -3;

     public static int CheckMifareClassicSupport(Tag nfcTag, Context appContext) {
         if(nfcTag == null || appContext == null) {
             return MFC_TAG_ERROR;
         }
         else if(Arrays.asList(nfcTag.getTechList()).contains(MifareClassic.class.getName())) {
             // Device and tag support MIFARE Classic.
             return MFC_FULL_SUPPORT;
         }
         else {
             // Check if device does not support MIFARE Classic.
             // For doing so, check if the SAK of the tag indicate that
             // it's a MIFARE Classic tag.
             // See: https://www.nxp.com/docs/en/application-note/AN10834.pdf
             NfcA nfca = NfcA.get(nfcTag);
             byte sak = (byte) nfca.getSak();
             if ((sak >> 1 & 1) == 1) { // RFU.
                 return NO_MFC_TAG_SUPPORT;
             } else {
                 if ((sak >> 3 & 1) == 1) { // SAK bit 4 = 1?
                     if ((sak >> 4 & 1) == 1) { // SAK bit 5 = 1?
                         // MIFARE Classic 4k
                         // MIFARE SmartMX 4K
                         // MIFARE PlusS 4K SL1
                         // MIFARE PlusX 4K SL1
                         return NO_MFC_DEVICE_SUPPORT;
                     } else {
                         if ((sak & 1) == 1) { // SAK bit 1 = 1?
                             // MIFARE Mini
                             return NO_MFC_DEVICE_SUPPORT;
                         } else {
                             // MIFARE Classic 1k
                             // MIFARE SmartMX 1k
                             // MIFARE PlusS 2K SL1
                             // MIFARE PlusX 2K SL2
                             return NO_MFC_DEVICE_SUPPORT;
                         }
                     }
                 } else {
                     // Some MIFARE tag, but not Classic or Classic compatible.
                     return NO_MFC_TAG_SUPPORT;
                 }
             }
         }
     }

     public static MifareClassicTag Decode(Tag nfcTag, String[] keyData, boolean displayGUIProgressBar) throws MifareClassicLibraryException {
         if(nfcTag == null) {
             throw new MifareClassicLibraryException(NoTagException);
         }
         else if(keyData == null) {
             throw new MifareClassicLibraryException(NoKeysFoundException);
         }
         else if(!CheckMFCKeys(keyData)) {
             throw new MifareClassicLibraryException(InvalidKeysException);
         }
         else if(!MifareClassicToolLibrary.Initialized()) {
             throw new MifareClassicLibraryException(GenericMFCException, "Uninitialized NFC tag data");
         }
         nfcTag = patchTag(nfcTag);
         int mfcSupportCode = 0;
         if((mfcSupportCode = CheckMifareClassicSupport(nfcTag, MifareClassicToolLibrary.GetApplicationContext())) != 0) {
             throw new MifareClassicLibraryException(UnsupportedTagException);
         }
         MifareClassicTag mfcTagData = new MifareClassicTag();
         if(!mfcTagData.ReadTagReservedData(nfcTag) ||
               !mfcTagData.IdentifyTag(nfcTag, mfcSupportCode) ||
               !mfcTagData.DumpTag(nfcTag, keyData, displayGUIProgressBar)) {
              return null;
         }
         return mfcTagData;
     }

     public static MifareClassicTag Decode(Tag nfcTag, boolean displayGUIProgressBar) throws MifareClassicLibraryException {
         String[] defaultKeyData = new String[] {
                 "000000000000",
                 MCTUtils.BytesToHexString(MifareClassic.KEY_DEFAULT),
                 MCTUtils.BytesToHexString(MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY),
                 MCTUtils.BytesToHexString(MifareClassic.KEY_NFC_FORUM)
         };
         return MifareClassicTag.Decode(nfcTag, defaultKeyData, displayGUIProgressBar);
     }

     public boolean ReadTagReservedData(Tag nfcTag) throws MifareClassicLibraryException {
         tagUID = MCTUtils.BytesToHexString(nfcTag.getId());
         NfcA nfcaTag = NfcA.get(nfcTag);
         // Swap ATQA to match the common order like shown here:
         // http://nfc-tools.org/index.php?title=ISO14443A
         byte[] atqaBytes = nfcaTag.getAtqa();
         atqaBytes = new byte[] {atqaBytes[1], atqaBytes[0]};
         tagATQA = MCTUtils.BytesToHexString(atqaBytes);
         // SAK in big endian.
         byte[] sakBytes = new byte[] {
                 (byte) ((nfcaTag.getSak() >> 8) & 0xFF),
                 (byte) (nfcaTag.getSak() & 0xFF)
         };
         // Print the first SAK byte only if it is not 0.
         if (sakBytes[0] != 0x00) {
             tagSAK = MCTUtils.BytesToHexString(sakBytes);
         }
         else {
             tagSAK = MCTUtils.BytesToHexString(new byte[] { sakBytes[1] });
         }
         tagATS = "-";
         IsoDep isoTag = IsoDep.get(nfcTag);
         if (isoTag != null) {
             byte[] atsBytes = isoTag.getHistoricalBytes();
             if (atsBytes != null && atsBytes.length > 0) {
                 tagATS = MCTUtils.BytesToHexString(atsBytes);
             }
         }
         return true;
     }

     public boolean IdentifyTag(Tag nfcTag, int mfcSupportCode) throws MifareClassicLibraryException {
         // Identify tag type (by string).
         boolean successCode = true;
         int tagTypeResourceID = GetTagIdentifier(tagATQA, tagSAK, tagATS);
         if (tagTypeResourceID == R.string.tag_unknown && mfcSupportCode > NO_MFC_TAG_SUPPORT) {
             mfcTagType = MifareClassicToolLibrary.GetApplicationContext().getString(R.string.tag_unknown_mf_classic);
             successCode = false;
         }
         else {
             mfcTagType = MifareClassicToolLibrary.GetApplicationContext().getString(tagTypeResourceID);
         }
         // read in the logistical layout data (e.g., number of sectors, tag size, etc.):
         if(mfcSupportCode != 0) {
             successCode = false;
         }
         else {
             MifareClassic mfcTag = MifareClassic.get(nfcTag);
             tagSize = mfcTag.getSize();
             tagSectorCount = mfcTag.getSectorCount();
             tagBlockCount = mfcTag.getBlockCount();
             tagBytesPerBlock = MifareClassic.BLOCK_SIZE;
         }
         return successCode;
     }

     public boolean DumpTag(Tag nfcTag, String[] keyData, boolean displayGUIProgressBar) throws MifareClassicLibraryException {
          if(nfcTag == null) {
               throw new MifareClassicLibraryException(NFCErrorException);
          }
          else if(keyData == null) {
               throw new MifareClassicLibraryException(InvalidKeysException);
          }
          mfcDumpImageData = new byte[tagSize];
          int mfcDumpDataArrayPos = 0, bytesRead = 0, sct = 0;
          while(sct < tagSectorCount) {
               if(displayGUIProgressBar) {
                    MifareClassicToolLibrary.DisplayProgressBar("SECTOR", sct + 1, tagSectorCount);
               }
               MFCSector nextSector = new MFCSector();
               nextSector.FromTag(nfcTag, sct);
               bytesRead = nextSector.ReadSector(nfcTag, keyData);
               if(bytesRead < nextSector.sectorSize) {
                    failedSectors.add(nextSector);
               }
               tagSectors.add(nextSector);
               sct++;
               if(nextSector.sectorBlockData == null) {
                    mfcDumpDataArrayPos += nextSector.sectorSize;
                    continue;
               }
               for(int blk = 0; blk < nextSector.sectorBlockCount; blk++) {
                    if(nextSector.sectorBlockData[blk] != null) {
                         System.arraycopy(nextSector.sectorBlockData[blk], 0, mfcDumpImageData,
                                          mfcDumpDataArrayPos, nextSector.sectorBytesPerBlock);
                    }
                    else {
                         return false;
                    }
                    mfcDumpDataArrayPos += nextSector.sectorBytesPerBlock;
               }
          }
          return true;
     }

    private int GetTagIdentifier(String atqa, String sak, String ats) {
        String prefix = "tag_";
        ats = ats.replace("-", "");
        // First check on ATQA + SAK + ATS.
        int ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                prefix + atqa + sak + ats, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        if (ret == 0) {
            // Check on ATQA + SAK.
            ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                    prefix + atqa + sak, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        }
        if (ret == 0) {
            // Check on ATQA.
            ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                    prefix + atqa, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        }
        if (ret == 0) {
            // No match found return "Unknown".
            return R.string.tag_unknown;
        }
        return ret;
    }

     public static boolean CheckMFCKeys(String[] keyDataList) {
         if(keyDataList == null) {
             return false;
         }
         for(int kidx = 0; kidx < keyDataList.length; kidx++) {
             if(!MCTUtils.IsHexAnd6Byte(keyDataList[kidx])) {
                 return false;
             }
         }
         return true;
     }

     public byte[] GetMFCDumpImageData() {
         return mfcDumpImageData;
     }

     public String GetTagType() {
         return mfcTagType;
     }

     public int GetTagSectors() {
         return tagSectorCount;
     }

     public int GetTagBlocks() {
         return tagBlockCount;
     }

     public int GetTagBytesPerBlock() {
         return tagBytesPerBlock;
     }

     public int GetTagSize() {
         return tagSize;
     }

     public List<MFCSector> GetFailedSectors() {
         return failedSectors;
     }

     public String GetRFTechCaps() {
         return rfTechCaps;
     }

     public String GetManufacturer() {
         return tagManufacturer;
     }

     public String GetTagUID() {
         return tagUID;
     }

     public int GetTagUIDSize() {
         return tagUID.length() / 2;
     }

     public String GetATQA() {
         return tagATQA;
     }

     public String GetSAK() {
         return tagSAK;
     }

     public String GetATS() {
         return tagATS;
     }

     public static String GetTagByteCountString(int byteCount) {
          if(byteCount == 1024) {
               return "1K";
          }
          else if(byteCount == 2048) {
               return "2K";
          }
          else if(byteCount == 4096) {
               return "4K";
          }
          else if(byteCount == 8192) {
               return "8K";
          }
          return String.valueOf(byteCount) + "B";
     }

     public String GetTagSizeSpecString() {
          String specString = String.format(Locale.US, "%s | %d Sectors x %d Blocks @ %sB",
                                            GetTagByteCountString(GetTagSize()), tagSectorCount,
                                            tagBlockCount / tagSectorCount, tagBytesPerBlock);
          return specString;
     }

     public boolean ExportToHexFile(String outputFile) throws IOException {
          if(outputFile == null || mfcDumpImageData == null) {
               return false;
          }
          PrintWriter printWriter = new PrintWriter(outputFile, "UTF-8");
          for(int blk = 0; blk < tagBlockCount; blk += tagBytesPerBlock) {
               byte[] blockBytes = new byte[tagBytesPerBlock];
               System.arraycopy(mfcDumpImageData, blk, blockBytes, 0, tagBytesPerBlock);
               printWriter.print(MCTUtils.BytesToHexString(blockBytes));
          }
          printWriter.close();
          return true;
     }

     public boolean ExportToBinaryDumpFile(String outputFile) throws IOException {
          if(outputFile == null || mfcDumpImageData == null) {
               return false;
          }
          FileOutputStream outStream = new FileOutputStream(outputFile);
          outStream.write(mfcDumpImageData);
          outStream.close();
          return true;
     }

     public MFCSector GetSectorByIndex(int index) {
          if(index < 0 || index >= tagSectors.size()) {
               return null;
          }
          return tagSectors.get(index);
     }

     public boolean GetSectorReadStatus(int index) {
          if(index < 0) {
               return false;
          }
          for(int fsec = 0; fsec < failedSectors.size(); fsec++) {
               if(failedSectors.get(fsec).sectorAddress == index) {
                    return false;
               }
          }
          return true;
     }

     public static final int MFCLASSIC1K_TAG_SIZE = 1024;
     public static final int MFCLASSIC1K_BLOCKS_PER_SECTOR = 4;
     public static final int MFCLASSIC_BLOCK_SIZE = 16;

     public static MifareClassicTag LoadMifareClassic1KFromResource(int resID) {

          if(!MifareClassicToolLibrary.Initialized()) {
               Log.e(TAG, "ERROR: MifareClassicToolLibrary NOT initialized!");
               return null;
          }

          // initialize statically "known" fields for a MFC1K tag:
          MifareClassicTag mfcTagData = new MifareClassicTag();
          mfcTagData.mfcTagType = "Mifare Classic 1K (From Dump Image)";
          mfcTagData.tagSize = MFCLASSIC1K_TAG_SIZE;
          mfcTagData.tagSectorCount = 16;
          mfcTagData.tagBytesPerBlock = MFCLASSIC_BLOCK_SIZE;
          mfcTagData.tagBlockCount = mfcTagData.tagSize / mfcTagData.tagBytesPerBlock;
          mfcTagData.tagManufacturer = "Unknown";

          // initialize the tag data bytes from the dump image resource:
          Context appMainContext = MifareClassicToolLibrary.GetApplicationContext();
          try {
               InputStream rawFileStream = appMainContext.getResources().openRawResource(resID);
               mfcTagData.mfcDumpImageData = new byte[MFCLASSIC1K_TAG_SIZE];
               int bytesReadCount = 0;
               byte[] byteReadBuffer = new byte[MFCLASSIC_BLOCK_SIZE];
               while (bytesReadCount < MFCLASSIC1K_TAG_SIZE) {
                    int readByteCount = rawFileStream.read(byteReadBuffer, 0, MFCLASSIC_BLOCK_SIZE);
                    if (readByteCount < 0) {
                         break;
                    }
                    System.arraycopy(byteReadBuffer, 0, mfcTagData.mfcDumpImageData, bytesReadCount, readByteCount);
                    bytesReadCount += readByteCount;
               }
               if(bytesReadCount < MFCLASSIC1K_TAG_SIZE) {
                    Log.e(TAG, "ERROR: Only able to load " + bytesReadCount + " of " + MFCLASSIC1K_TAG_SIZE + "bytes from tag!");
                    return null;
               }
          } catch(IOException ioe) {
               ioe.printStackTrace();
               return null;
          }
          // setup the individual sector data:
          for(int sec = 0; sec < mfcTagData.tagSectorCount; sec++) {
               MFCSector nextSector = new MFCSector();
               nextSector.sectorAddress = sec;
               nextSector.sectorSize = mfcTagData.tagBlockCount * mfcTagData.tagBytesPerBlock;
               nextSector.sectorBlockCount = mfcTagData.tagBlockCount;
               nextSector.sectorFirstBlock = sec * MFCLASSIC1K_BLOCKS_PER_SECTOR;
               nextSector.sectorBytesPerBlock = mfcTagData.tagBytesPerBlock;
               nextSector.sectorBlockData = new byte[MFCLASSIC1K_BLOCKS_PER_SECTOR][];
               for(int blk = 0; blk < MFCLASSIC1K_BLOCKS_PER_SECTOR; blk++) {
                    byte[] blockBytes = new byte[mfcTagData.tagBytesPerBlock];
                    System.arraycopy(mfcTagData.mfcDumpImageData, sec * MFCLASSIC1K_BLOCKS_PER_SECTOR + blk,
                                     blockBytes, 0, mfcTagData.tagBytesPerBlock);
                    nextSector.sectorBlockData[blk] = blockBytes;
                    if(blk == MFCLASSIC1K_BLOCKS_PER_SECTOR - 1) {
                         nextSector.keyA = new byte[6];
                         System.arraycopy(blockBytes, 0, nextSector.keyA, 0, 6);
                         nextSector.sectorAccessBits = new byte[4];
                         System.arraycopy(blockBytes, 6, nextSector.sectorAccessBits, 0, 4);
                         nextSector.keyB = new byte[6];
                         System.arraycopy(blockBytes, 10, nextSector.keyB, 0, 6);
                    }
               }
               mfcTagData.tagSectors.add(nextSector);
          }
          // load the rest of the first block (tag read-only) sector data for accounting:
          byte[] uidBytes = new byte[4];
          System.arraycopy(mfcTagData.mfcDumpImageData, 0, uidBytes, 0, 4);
          mfcTagData.tagUID = MCTUtils.BytesToHexString(uidBytes);
          byte sakByte = mfcTagData.mfcDumpImageData[5];
          mfcTagData.tagSAK = MCTUtils.BytesToHexString(new byte[] { sakByte });
          byte[] atqaBytes = new byte[2];
          System.arraycopy(mfcTagData.mfcDumpImageData, 6, atqaBytes, 0, 2);
          mfcTagData.tagATQA = MCTUtils.BytesToHexString(atqaBytes);
          mfcTagData.tagATS = "Unknown ATS";
          byte[] manuBytes = new byte[8];
          System.arraycopy(mfcTagData.mfcDumpImageData, 8, manuBytes, 0, 8);
          mfcTagData.tagManufacturer = MCTUtils.BytesToHexString(manuBytes);
          return mfcTagData;

     }

}