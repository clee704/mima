/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

package com.typesafe.tools.mima.core

import java.io.IOException

import scala.tools.nsc.symtab.classfile.ClassfileConstants._

final class ClassfileParser(definitions: Definitions) {
  import ClassfileParser._

  private var in: BufferReader = _  // the class file reader
  private var thepool: ConstantPool = _

  def pool: ConstantPool = thepool

  def parse(clazz: ClassInfo) = synchronized {
    in = new BufferReader(clazz.file.toByteArray)
    parseAll(clazz)
  }

  protected def parseAll(clazz: ClassInfo): Unit = {
    parseHeader(clazz)
    thepool = ConstantPool.parseNew(definitions, in)
    parseClass(clazz)
  }

  protected def parseHeader(clazz: ClassInfo): Unit = {
    val magic = in.nextInt
    if (magic != JAVA_MAGIC)
      throw new IOException("class file '" + clazz.file + "' "
                            + "has wrong magic number 0x" + magic.toHexString
                            + ", should be 0x" + JAVA_MAGIC.toHexString)
    in.nextChar.toInt // minorVersion
    in.nextChar.toInt // majorVersion
  }

  private def parseMembers[A <: MemberInfo : MkMember](clazz: ClassInfo): Members[A] = {
    val members = {
      for {
        _ <- 0.until(in.nextChar).iterator
        flags = in.nextChar
        if !isPrivate(flags) || {
          in.skip(4)
          for (_ <- 0 until in.nextChar) {
            in.skip(2)
            in.skip(in.nextInt)
          }
          false
        }
      } yield parseMember[A](clazz, flags)
    }.toList
    new Members[A](members)
  }

  private def parseMember[A <: MemberInfo : MkMember](clazz: ClassInfo, flags: Int): A = {
    val name = pool.getName(in.nextChar)
    val descriptor = pool.getExternalName(in.nextChar)
    val result = implicitly[MkMember[A]].make(clazz, name, flags, descriptor)
    parseAttributes(result)
    result
  }

  def parseClass(clazz: ClassInfo): Unit = {
    clazz.flags = in.nextChar
    val nameIdx = in.nextChar
    pool.getClassName(nameIdx) // externalName

    def parseSuperClass(): ClassInfo =
      if (hasAnnotation(clazz.flags)) { in.nextChar; definitions.AnnotationClass }
      else pool.getSuperClass(in.nextChar)

    def parseInterfaces(): List[ClassInfo] = {
      val rawInterfaces =
        for (_ <- List.range(0, in.nextChar)) yield pool.getSuperClass(in.nextChar)
      rawInterfaces filter (_ != NoClass)
    }

    clazz.superClass = parseSuperClass()
    clazz.interfaces = parseInterfaces()
    clazz.fields     = parseMembers[FieldInfo](clazz)
    clazz.methods    = parseMembers[MethodInfo](clazz)
    parseAttributes(clazz)
  }

  def skipAttributes(): Unit = {
    val attrCount = in.nextChar
    for (_ <- 0 until attrCount) {
      in.skip(2); in.skip(in.nextInt)
    }
  }

  def parseAttributes(c: ClassInfo): Unit = {
    val attrCount = in.nextChar
     for (_ <- 0 until attrCount) {
       val attrIndex = in.nextChar
       val attrLen = in.nextInt
       val attrEnd = in.bp + attrLen
       pool.getName(attrIndex) match {
         case "EnclosingMethod" => c._isLocalClass = true
         case "InnerClasses"    =>
           c._innerClasses = (0 until in.nextChar).map { _ =>
             val innerIndex, outerIndex, innerNameIndex = in.nextChar.toInt
             in.skip(2)
             if (innerIndex != 0 && outerIndex != 0 && innerNameIndex != 0) {
               val n = pool.getClassName(innerIndex)
               if (n == c.bytecodeName) c._isTopLevel = false // an inner class lists itself in InnerClasses
               if (pool.getClassName(outerIndex) == c.bytecodeName) n else ""
             } else ""
           }.filterNot(_.isEmpty)
         case _                 => ()
       }
       in.bp = attrEnd
     }
  }

  def parseAttributes(m: MemberInfo): Unit = {
    val attrCount = in.nextChar
    for (_ <- 0 until attrCount) {
      val attrIndex = in.nextChar
      val attrLen = in.nextInt
      val attrEnd = in.bp + attrLen
      pool.getName(attrIndex) match {
        case "Deprecated" => m.isDeprecated = true
        case "Signature"  => m.signature = pool.getName(in.nextChar)
        case _            => ()
      }
      in.bp = attrEnd
    }
  }
}

object ClassfileParser {
  @inline final def isPublic(flags: Int) =
    (flags & JAVA_ACC_PUBLIC) != 0
  @inline final def isProtected(flags: Int) =
    (flags & JAVA_ACC_PROTECTED) != 0
  @inline final def isPrivate(flags: Int) =
    (flags & JAVA_ACC_PRIVATE) != 0
  @inline final def isStatic(flags: Int) =
    (flags & JAVA_ACC_STATIC) != 0
  @inline final private def hasAnnotation(flags: Int) =
    (flags & JAVA_ACC_ANNOTATION) != 0
  @inline final def isInterface(flags: Int) =
    (flags & JAVA_ACC_INTERFACE) != 0
  @inline final def isDeferred(flags: Int) =
    (flags & JAVA_ACC_ABSTRACT) != 0
  @inline final def isFinal(flags: Int) =
    (flags & JAVA_ACC_FINAL) != 0
  @inline final def isSynthetic(flags: Int) =
    (flags & JAVA_ACC_SYNTHETIC) != 0
  @inline final def isBridge(flags: Int) =
    (flags & JAVA_ACC_BRIDGE) != 0

  private trait MkMember[A] {
    def make(owner: ClassInfo, bytecodeName: String, flags: Int, descriptor: String): A
  }

  private implicit def mkFieldInfo: MkMember[FieldInfo]   = new FieldInfo(_, _, _, _)
  private implicit def mkMethodInfo: MkMember[MethodInfo] = new MethodInfo(_, _, _, _)
}
