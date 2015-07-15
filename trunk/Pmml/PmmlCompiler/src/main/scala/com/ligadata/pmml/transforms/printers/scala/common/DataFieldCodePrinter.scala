package com.ligadata.pmml.compiler

import scala.collection.mutable._
import scala.math._
import scala.collection.immutable.StringLike
import scala.util.control.Breaks._
import com.ligadata.pmml.runtime._
import org.apache.log4j.Logger
import com.ligadata.fatafat.metadata._

class DataFieldCodePrinter(ctx : PmmlContext) {

	/**
	 *  Answer a string (code representation) for the supplied node.
	 *  @param node the PmmlExecNode
	 *  @param the CodePrinterDispatch to use should recursion to child nodes be required.
 	    @param the kind of code fragment to generate...any 
 	    	{VARDECL, VALDECL, FUNCCALL, DERIVEDCLASS, RULECLASS, RULESETCLASS , MININGFIELD, MAPVALUE, AGGREGATE, USERFUNCTION}
	 *  @order the traversalOrder to traverse this node...any {INORDER, PREORDER, POSTORDER} 
	 *  
	 *  @return some string representation of this node
	 */
	def print(node : Option[PmmlExecNode]
			, generator : CodePrinterDispatch
			, kind : CodeFragment.Kind
			, traversalOrder : Traversal.Order) : String = {

		val xnode : xDataField = node match {
			case Some(node) => {
				if (node.isInstanceOf[xDataField]) node.asInstanceOf[xDataField] else null
			}
			case _ => null
		}

		val printThis = if (xnode != null) {
			codeGenerator(xnode, generator, kind, traversalOrder)
		} else {
			if (node != null) {
				PmmlError.logError(ctx, s"For ${node.qName}, expecting an xDataField... got a ${node.getClass.getName}... check CodePrinter dispatch map initialization")
			}
			""
		}
		printThis
	}
	
	private def codeGenerator(node : xDataField
							, generator : CodePrinterDispatch
							, kind : CodeFragment.Kind
							, traversalOrder : Traversal.Order) : String =
	{
		val typeStr : String = PmmlTypes.scalaDataType(node.dataType)
		
		val fieldDecl : String = order match {
			case Traversal.INORDER => { "" }
			case Traversal.POSTORDER => { "" }
			case Traversal.PREORDER => {
				generate match {
					case CodeFragment.VARDECL => {
						s"val ${node.name} : $typeStr "
					}
					case CodeFragment.VALDECL => {
						s"var ${node.name} : $typeStr "						
					}
					case CodeFragment.FUNCCALL => {
						/** generate the code to fetch the value */ 
						s"ctx.valueFor(${'"'}${node.name}${'"'})"
					} 
					case _ => { 
						PmmlError.logError(ctx, "DataField node - unsupported CodeFragment.Kind") 
						""
					}
				}
			}
		}
		fieldDecl
	}

}


